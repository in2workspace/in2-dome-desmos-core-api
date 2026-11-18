package es.in2.desmos.domain.services.api.impl;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.models.AuditRecordStatus;
import es.in2.desmos.domain.models.AuditRecordTrader;
import es.in2.desmos.domain.models.MVAuditServiceEntity4DataNegotiation;
import es.in2.desmos.domain.repositories.AuditRecordRepository;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import es.in2.desmos.domain.utils.ApplicationUtils;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.infrastructure.configs.EndpointsConfig;
import es.in2.desmos.infrastructure.configs.properties.AuditProperties;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.R2dbcException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.data.relational.core.query.Query;
import org.springframework.r2dbc.BadSqlGrammarException;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The batch writer of the audit chain against a real PostgreSQL, with the application's migrations. No Spring context:
 * the writer, the repository and the transaction manager are wired by hand on one connection pool.
 */
class AuditChainBatchWriterIT {

    private static final String PROCESS_ID = "it";
    private static final String SOURCE = "p2p sync";
    private static final TimeZone ORIGINAL_TIME_ZONE = TimeZone.getDefault();

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:latest");

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .build();

    private static ConnectionFactory connectionFactory;
    private static DatabaseClient databaseClient;
    private static R2dbcEntityTemplate template;
    private static AuditRecordRepository repository;

    @BeforeAll
    static void startDatabase() {
        // Outside UTC, like the existing ITs: the driver sets the session time zone from the JVM's when it connects
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid"));
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("desmos")
                .load()
                .migrate();
        connectionFactory = connectionFactory(10);
        databaseClient = DatabaseClient.create(connectionFactory);
        template = new R2dbcEntityTemplate(connectionFactory);
        repository = new R2dbcRepositoryFactory(template).getRepository(AuditRecordRepository.class);
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
        TimeZone.setDefault(ORIGINAL_TIME_ZONE);
    }

    @BeforeEach
    void emptyTable() {
        databaseClient.sql("TRUNCATE desmos.audit_records").then().block();
    }

    private static ConnectionFactory connectionFactory(int maxSize) {
        return ConnectionFactories.get("r2dbc:pool:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                + "@" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName()
                + "?maxSize=" + maxSize + "&initialSize=1");
    }

    private static AuditChainBatchWriter writer() {
        return writer(new AuditRecordStatements(databaseClient, new AuditProperties(500)), new LatestAuditRecordReader(repository));
    }

    private static AuditChainBatchWriter writer(AuditRecordStatements statements, LatestAuditRecordReader reader) {
        return new AuditChainBatchWriter(reader, statements, OBJECT_MAPPER, new R2dbcTransactionManager(connectionFactory),
                new AuditProperties(500), new ReactiveMutex(), Clock.systemDefaultZone());
    }

    @Test
    void aBatchWriteLeavesALinearChain() {
        StepVerifier.create(writer().append(PROCESS_ID, drafts("a", 1000), SOURCE))
                .expectComplete()
                .verify(Duration.ofSeconds(60));

        List<AuditRecord> rows = allRowsByCreatedAt();
        assertThat(rows).hasSize(1000);
        assertLinearChain(rows);
    }

    @Test
    void theIndexOnCreatedAtServesTheReadOfTheLastRecord() {
        writer().append(PROCESS_ID, drafts("a", 1000), SOURCE).block(Duration.ofSeconds(60));
        databaseClient.sql("ANALYZE desmos.audit_records").then().block();

        String plan = databaseClient.sql("EXPLAIN SELECT * FROM desmos.audit_records ORDER BY created_at DESC LIMIT 1")
                .map(row -> row.get(0, String.class))
                .all()
                .collectList()
                .map(lines -> String.join("\n", lines))
                .block();

        assertThat(plan).contains("idx_audit_records_created_at");
    }

    @Test
    void twoConcurrentBatchesOfTheSameReplicaLeaveALinearChain() {
        AuditChainBatchWriter writer = writer();

        StepVerifier.create(Flux.just(drafts("a", 500), drafts("b", 500))
                        .flatMap(drafts -> writer.append(PROCESS_ID, drafts, SOURCE).subscribeOn(Schedulers.parallel()))
                        .then())
                .expectComplete()
                .verify(Duration.ofSeconds(60));

        List<AuditRecord> rows = allRowsByCreatedAt();
        assertThat(rows).hasSize(1000);
        assertLinearChain(rows);
    }

    // Two writers, each with its own in-memory mutex, simulate two replicas: only the advisory lock serializes them.
    // The read of the last record is slowed down so that, without the lock, the two would read the same one.
    @Test
    void twoReplicasLeaveALinearChainThanksToTheAdvisoryLock() {
        AuditRecordStatements statements = new AuditRecordStatements(databaseClient, new AuditProperties(500));

        writeAsTwoReplicas(statements);

        List<AuditRecord> rows = allRowsByCreatedAt();
        assertThat(rows).hasSize(4000);
        assertLinearChain(rows);
    }

    // Control for the previous test: without the advisory lock the same scenario forks the chain
    @Test
    void withoutTheAdvisoryLockTwoReplicasForkTheChain() {
        AuditRecordStatements withoutLock = new AuditRecordStatements(databaseClient, new AuditProperties(500)) {
            @Override
            Mono<Void> lockChain() {
                return Mono.empty();
            }
        };

        writeAsTwoReplicas(withoutLock);

        assertThat(countChainBreaks(allRowsByCreatedAt())).isPositive();
    }

    private static void writeAsTwoReplicas(AuditRecordStatements statements) {
        LatestAuditRecordReader slowReader = new LatestAuditRecordReader(repository) {
            @Override
            public Mono<AuditRecord> fetch() {
                return super.fetch().delayElement(Duration.ofMillis(100));
            }
        };
        AuditChainBatchWriter replica1 = writer(statements, slowReader);
        AuditChainBatchWriter replica2 = writer(statements, slowReader);

        StepVerifier.create(Flux.just(replica1, replica2)
                        .zipWith(Flux.just(drafts("a", 2000), drafts("b", 2000)))
                        .flatMap(pair -> pair.getT1().append(PROCESS_ID, pair.getT2(), SOURCE).subscribeOn(Schedulers.parallel()))
                        .then())
                .expectComplete()
                .verify(Duration.ofSeconds(120));
    }

    @Test
    void theLastRecordIsReadWithMicroseconds() {
        Timestamp createdAt = Timestamp.valueOf(LocalDateTime.parse("2026-11-17T10:00:00.123456"));
        repository.save(AuditRecord.builder().id(UUID.randomUUID()).processId(PROCESS_ID).createdAt(createdAt)
                .entityId("urn:e:x").status(AuditRecordStatus.RECEIVED).trader(AuditRecordTrader.CONSUMER)
                .hash("h").hashLink("hl").newTransaction(true).build()).block();

        AuditRecord last = new LatestAuditRecordReader(repository).fetch().block();

        assertThat(last).isNotNull();
        assertThat(last.getCreatedAt()).isEqualTo(createdAt);
        assertThat(last.getCreatedAt().getNanos()).isEqualTo(123_456_000);
    }

    // The notifications save through repository.save, the sync through the multi-row INSERT: created_at must be stored
    // the same way by both, or ORDER BY created_at would chain on the wrong record
    @Test
    void batchesInterleavedWithRepositorySavesKeepTheWriteOrder() {
        AuditChainBatchWriter writer = writer();
        LatestAuditRecordReader reader = new LatestAuditRecordReader(repository);
        List<UUID> writeOrder = new ArrayList<>();

        for (int round = 0; round < 5; round++) {
            List<AuditRecord> drafts = drafts("round" + round, 3);
            writer.append(PROCESS_ID, drafts, SOURCE).block(Duration.ofSeconds(30));
            drafts.forEach(draft -> writeOrder.add(draft.getId()));

            AuditRecord notification = saveLikeANotification(reader, "urn:notification:" + round);
            writeOrder.add(notification.getId());
        }

        List<AuditRecord> rows = allRowsByCreatedAt();
        assertThat(rows).extracting(AuditRecord::getId).containsExactlyElementsOf(writeOrder);
        assertLinearChain(rows);
    }

    @Test
    void aRowTooLongMidChunkKeepsThePreviousRowsAndStopsTheFollowingChunks() {
        List<AuditRecord> drafts = new ArrayList<>(drafts("a", 700));
        drafts.set(200, drafts.get(200).toBuilder().dataLocation("x".repeat(300)).build());

        StepVerifier.create(writer().append(PROCESS_ID, drafts, SOURCE))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(BadSqlGrammarException.class);
                    assertThat(error.getCause()).isInstanceOf(R2dbcException.class);
                    assertThat(((R2dbcException) error.getCause()).getSqlState()).isEqualTo("22001");
                })
                .verify(Duration.ofSeconds(60));

        List<AuditRecord> rows = allRowsByCreatedAt();
        assertThat(rows).extracting(AuditRecord::getId)
                .containsExactlyElementsOf(drafts.subList(0, 200).stream().map(AuditRecord::getId).toList());
        assertLinearChain(rows);
    }

    @Test
    void aRowTooLongInTheSecondChunkKeepsTheFirstChunkAndThePrefixOfTheSecond() {
        List<AuditRecord> drafts = new ArrayList<>(drafts("a", 1000));
        drafts.set(600, drafts.get(600).toBuilder().dataLocation("x".repeat(300)).build());

        StepVerifier.create(writer().append(PROCESS_ID, drafts, SOURCE))
                .expectError(BadSqlGrammarException.class)
                .verify(Duration.ofSeconds(60));

        List<AuditRecord> rows = allRowsByCreatedAt();
        assertThat(rows).extracting(AuditRecord::getId)
                .containsExactlyElementsOf(drafts.subList(0, 600).stream().map(AuditRecord::getId).toList());
        assertLinearChain(rows);
    }

    // 499 rows are 4 statements of 100, 9 of 10 and 9 of 1; row 450 falls in a statement of 10. The batch rolls back as a
    // whole: if an earlier statement had stayed, the row-by-row path would fail on a duplicate id at once.
    @Test
    void aFailingStatementThatIsNotTheFirstRollsBackTheWholeChunk() {
        List<AuditRecord> drafts = new ArrayList<>(drafts("a", 499));
        drafts.set(450, drafts.get(450).toBuilder().dataLocation("x".repeat(300)).build());

        StepVerifier.create(writer().append(PROCESS_ID, drafts, SOURCE))
                .expectErrorSatisfies(error ->
                        assertThat(((R2dbcException) error.getCause()).getSqlState()).isEqualTo("22001"))
                .verify(Duration.ofSeconds(60));

        List<AuditRecord> rows = allRowsByCreatedAt();
        assertThat(rows).extracting(AuditRecord::getId)
                .containsExactlyElementsOf(drafts.subList(0, 450).stream().map(AuditRecord::getId).toList());
        assertLinearChain(rows);
    }

    @Test
    void onlyTheFourFixedInsertSizesArePreparedOnAConnection() {
        ConnectionFactory singleConnection = connectionFactory(1);
        DatabaseClient client = DatabaseClient.create(singleConnection);
        R2dbcEntityTemplate singleTemplate = new R2dbcEntityTemplate(singleConnection);
        AuditRecordRepository singleRepository = new R2dbcRepositoryFactory(singleTemplate).getRepository(AuditRecordRepository.class);
        AuditChainBatchWriter writer = new AuditChainBatchWriter(new LatestAuditRecordReader(singleRepository),
                new AuditRecordStatements(client, new AuditProperties(500)), OBJECT_MAPPER,
                new R2dbcTransactionManager(singleConnection), new AuditProperties(500), new ReactiveMutex(), Clock.systemDefaultZone());

        for (int size : new int[]{1, 7, 23, 99, 101, 250, 377, 499, 500, 731, 1234}) {
            writer.append(PROCESS_ID, drafts("s" + size, size), SOURCE).block(Duration.ofSeconds(60));
        }

        List<String> preparedInserts = client.sql("SELECT statement FROM pg_prepared_statements")
                .map(row -> row.get(0, String.class))
                .all()
                .filter(statement -> statement.startsWith("INSERT INTO desmos.audit_records"))
                .collectList()
                .block();
        assertThat(preparedInserts).isNotEmpty();
        assertThat(preparedInserts).extracting(statement -> statement.split("\\), \\(").length)
                .containsOnly(500, 100, 10, 1)
                .doesNotHaveDuplicates();
    }

    // Before the batch writer, two step-0 runs in parallel (our sync and a discovery requested by another node) forked
    // the chain: every entity read the same "last" record. The broker mock releases every hash at once, so that every
    // entity is past the broker read at the same time.
    @Test
    void twoParallelStep0RunsLeaveALinearChain() {
        int entitiesPerRun = 50;
        Sinks.Empty<Void> allArrived = Sinks.empty();
        AtomicInteger arrived = new AtomicInteger();
        BrokerPublisherService broker = mock(BrokerPublisherService.class);
        when(broker.getEntityById(eq(PROCESS_ID), any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(1);
            if (arrived.incrementAndGet() == 2 * entitiesPerRun) {
                allArrived.tryEmitEmpty();
            }
            return allArrived.asMono().thenReturn("{\"id\":\"" + id + "\",\"type\":\"product-offering\"}");
        });
        ApiConfig apiConfig = mock(ApiConfig.class);
        when(apiConfig.getExternalDomain()).thenReturn("http://node-a");
        EndpointsConfig endpointsConfig = mock(EndpointsConfig.class);
        when(endpointsConfig.getEntitiesEndpoint()).thenReturn("/api/v1/entities");
        LatestAuditRecordReader reader = new LatestAuditRecordReader(repository);
        AuditRecordServiceImpl service = new AuditRecordServiceImpl(OBJECT_MAPPER, repository, broker, apiConfig,
                endpointsConfig, reader, writer());

        StepVerifier.create(Flux.just("a", "b")
                        .flatMap(run -> service.findCreateOrUpdateAuditRecordsByEntityIds(PROCESS_ID, "product-offering",
                                Flux.range(0, entitiesPerRun).map(i -> "urn:" + run + ":" + i)).collectList())
                        .collectList())
                .assertNext(results -> assertThat(results).allSatisfy(result -> assertThat(result).hasSize(entitiesPerRun)))
                .expectComplete()
                .verify(Duration.ofSeconds(60));

        List<AuditRecord> rows = allRowsByCreatedAt();
        assertThat(rows).hasSize(2 * entitiesPerRun)
                .allSatisfy(row -> {
                    assertThat(row.getStatus()).isEqualTo(AuditRecordStatus.PUBLISHED);
                    assertThat(row.getTrader()).isEqualTo(AuditRecordTrader.PRODUCER);
                });
        assertLinearChain(rows);
    }

    @Test
    void theSyncBatchMethodWritesOneConsumerRecordPerEntity() {
        AuditRecordServiceImpl service = new AuditRecordServiceImpl(OBJECT_MAPPER, repository,
                mock(BrokerPublisherService.class), mock(ApiConfig.class), mock(EndpointsConfig.class),
                new LatestAuditRecordReader(repository), writer());
        List<MVAuditServiceEntity4DataNegotiation> entities = IntStream.range(0, 3)
                .mapToObj(i -> new MVAuditServiceEntity4DataNegotiation("urn:e:" + i, "product-offering", "h" + i, "hl" + i))
                .toList();

        service.buildAndSaveAuditRecordsFromDataSync(PROCESS_ID, entities, AuditRecordStatus.RETRIEVED).block();

        List<AuditRecord> rows = allRowsByCreatedAt();
        assertThat(rows).extracting(AuditRecord::getEntityId).containsExactly("urn:e:0", "urn:e:1", "urn:e:2");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(AuditRecordStatus.RETRIEVED);
            assertThat(row.getTrader()).isEqualTo(AuditRecordTrader.CONSUMER);
            assertThat(row.getDataLocation()).isEmpty();
        });
        assertLinearChain(rows);
    }

    // --- helpers ---

    private static List<AuditRecord> drafts(String prefix, int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> AuditRecord.builder()
                        .id(UUID.randomUUID())
                        .processId(PROCESS_ID)
                        .entityId("urn:" + prefix + ":" + i)
                        .entityType("product-offering")
                        .entityHash("eh-" + prefix + "-" + i)
                        .entityHashLink("ehl-" + prefix + "-" + i)
                        .dataLocation("")
                        .status(AuditRecordStatus.RETRIEVED)
                        .trader(AuditRecordTrader.CONSUMER)
                        .newTransaction(true)
                        .build())
                .toList();
    }

    // What AuditRecordServiceImpl does for a notification: read the last record, chain on it, repository.save
    private static AuditRecord saveLikeANotification(LatestAuditRecordReader reader, String entityId) {
        AuditRecord last = reader.fetch().block();
        AuditRecord auditRecord = AuditRecord.builder()
                .id(UUID.randomUUID())
                .processId(PROCESS_ID)
                .createdAt(Timestamp.from(Instant.now()))
                .entityId(entityId)
                .entityType("product-offering")
                .entityHash("")
                .entityHashLink("")
                .dataLocation("")
                .status(AuditRecordStatus.RECEIVED)
                .trader(AuditRecordTrader.PRODUCER)
                .hash("")
                .hashLink("")
                .newTransaction(true)
                .build();
        try {
            String hash = ApplicationUtils.calculateSHA256(OBJECT_MAPPER.writeValueAsString(auditRecord));
            auditRecord.setHash(hash);
            auditRecord.setHashLink(last == null || last.getHashLink() == null ? hash
                    : ApplicationUtils.calculateHashLink(last.getHashLink(), hash));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return repository.save(auditRecord).block();
    }

    private static List<AuditRecord> allRowsByCreatedAt() {
        return template.select(Query.empty().sort(Sort.by("created_at")), AuditRecord.class)
                .collectList()
                .block();
    }

    private static void assertLinearChain(List<AuditRecord> rows) {
        assertThat(countChainBreaks(rows)).as("rows whose hash or hashLink does not follow the previous row").isZero();
        for (int i = 1; i < rows.size(); i++) {
            assertThat(rows.get(i).getCreatedAt()).isAfter(rows.get(i - 1).getCreatedAt());
        }
    }

    // Walks the table by created_at, recomputing hash and hashLink of every row from the previous one
    private static int countChainBreaks(List<AuditRecord> rows) {
        int breaks = 0;
        String previousHashLink = null;
        for (AuditRecord row : rows) {
            try {
                // newTransaction is not stored; every record is hashed with it set to true
                AuditRecord unhashed = row.toBuilder().hash("").hashLink("").newTransaction(true).build();
                String hash = ApplicationUtils.calculateSHA256(OBJECT_MAPPER.writeValueAsString(unhashed));
                String expectedHashLink = previousHashLink == null ? hash : ApplicationUtils.calculateHashLink(previousHashLink, hash);
                if (!hash.equals(row.getHash()) || !expectedHashLink.equals(row.getHashLink())) {
                    breaks++;
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            previousHashLink = row.getHashLink();
        }
        return breaks;
    }
}
