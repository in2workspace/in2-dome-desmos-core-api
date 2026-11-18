package es.in2.desmos.domain.services.api.impl;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.models.AuditRecordStatus;
import es.in2.desmos.domain.models.AuditRecordTrader;
import es.in2.desmos.domain.utils.ApplicationUtils;
import es.in2.desmos.infrastructure.configs.properties.AuditProperties;
import es.in2.desmos.testutils.LogCapture;
import io.r2dbc.spi.R2dbcBadGrammarException;
import io.r2dbc.spi.R2dbcDataIntegrityViolationException;
import io.r2dbc.spi.R2dbcNonTransientResourceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.BadSqlGrammarException;
import org.springframework.transaction.ReactiveTransaction;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuditChainBatchWriterTest {

    private static final String PROCESS_ID = "process-1";
    private static final String SOURCE = "p2p sync";
    private static final Instant T0 = Instant.parse("2026-11-17T10:00:00.123456789Z");

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .build();
    private final AuditRecordStatements statements = mock(AuditRecordStatements.class);
    private final LatestAuditRecordReader reader = mock(LatestAuditRecordReader.class);
    private final FakeTransactionManager transactionManager = new FakeTransactionManager();
    private final MutableClock clock = new MutableClock(T0);

    // The rows inserted (and committed or not) through the statements mock, in order
    private final List<AuditRecord> stored = Collections.synchronizedList(new ArrayList<>());
    private AuditRecord initialLast = AuditRecord.builder().build();
    private Function<List<AuditRecord>, Mono<Void>> insertBehaviour = rows -> Mono.empty();

    @BeforeEach
    void setUp() {
        when(statements.lockChain()).thenReturn(Mono.empty());
        when(statements.insert(any())).thenAnswer(invocation -> {
            List<AuditRecord> rows = invocation.getArgument(0);
            return Mono.defer(() -> insertBehaviour.apply(rows)).doOnSuccess(unused -> stored.addAll(rows));
        });
        when(reader.fetch()).thenAnswer(invocation -> Mono.fromSupplier(() ->
                stored.isEmpty() ? initialLast : stored.get(stored.size() - 1)));
    }

    private AuditChainBatchWriter writer(int batchSize) {
        return writer(batchSize, objectMapper);
    }

    private AuditChainBatchWriter writer(int batchSize, ObjectMapper mapper) {
        return new AuditChainBatchWriter(reader, statements, mapper, transactionManager, new AuditProperties(batchSize),
                new ReactiveMutex(Schedulers.immediate()), clock);
    }

    @Test
    void itShouldChainTheBatchOnTheLastExistingRecord() throws Exception {
        initialLast = AuditRecord.builder().createdAt(Timestamp.from(T0.minusSeconds(1))).hashLink("previous-hashlink").build();
        List<AuditRecord> drafts = drafts(5);

        StepVerifier.create(writer(500).append(PROCESS_ID, drafts, SOURCE)).verifyComplete();

        assertThat(stored).hasSize(5);
        assertLinearChain("previous-hashlink", stored);
        assertThat(stored).extracting(AuditRecord::getId).containsExactlyElementsOf(drafts.stream().map(AuditRecord::getId).toList());
        assertThat(transactionManager.commits).hasValue(1);
        verify(statements, times(1)).insert(any());
    }

    @Test
    void itShouldNotModifyTheDrafts() {
        List<AuditRecord> drafts = drafts(3);

        StepVerifier.create(writer(500).append(PROCESS_ID, drafts, SOURCE)).verifyComplete();

        assertThat(drafts).allSatisfy(draft -> {
            assertThat(draft.getCreatedAt()).isNull();
            assertThat(draft.getHash()).isNull();
            assertThat(draft.getHashLink()).isNull();
        });
    }

    @Test
    void theFirstRowOfAnEmptyTableLinksToItself() throws Exception {
        StepVerifier.create(writer(500).append(PROCESS_ID, drafts(2), SOURCE)).verifyComplete();

        assertThat(stored.get(0).getHashLink()).isEqualTo(stored.get(0).getHash());
        assertThat(stored.get(0).getCreatedAt().toInstant()).isEqualTo(T0.truncatedTo(ChronoUnit.MICROS));
        assertLinearChain(null, stored);
    }

    @Test
    void createdAtIsStrictlyIncreasingWhenTheClockStandsStill() {
        StepVerifier.create(writer(500).append(PROCESS_ID, drafts(4), SOURCE)).verifyComplete();

        Instant first = T0.truncatedTo(ChronoUnit.MICROS);
        assertThat(stored).extracting(row -> row.getCreatedAt().toInstant())
                .containsExactly(first, first.plus(1, ChronoUnit.MICROS), first.plus(2, ChronoUnit.MICROS),
                        first.plus(3, ChronoUnit.MICROS));
    }

    @Test
    void createdAtNeverGoesBackEvenIfTheClockDoes() {
        Instant lastCreatedAt = T0.plusSeconds(10).truncatedTo(ChronoUnit.MICROS);
        initialLast = AuditRecord.builder().createdAt(Timestamp.from(lastCreatedAt)).hashLink("hl").build();
        clock.ticks(Duration.ofSeconds(-1));

        StepVerifier.create(writer(500).append(PROCESS_ID, drafts(3), SOURCE)).verifyComplete();

        assertThat(stored.get(0).getCreatedAt().toInstant()).isAfter(lastCreatedAt);
        assertStrictlyIncreasing(stored);
    }

    @Test
    void createdAtFollowsTheClockReadForEveryRow() {
        clock.ticks(Duration.ofMillis(5));

        StepVerifier.create(writer(500).append(PROCESS_ID, drafts(3), SOURCE)).verifyComplete();

        assertThat(stored).extracting(row -> row.getCreatedAt().toInstant())
                .containsExactly(T0.truncatedTo(ChronoUnit.MICROS), T0.plusMillis(5).truncatedTo(ChronoUnit.MICROS),
                        T0.plusMillis(10).truncatedTo(ChronoUnit.MICROS));
    }

    // created_at is stored as local time: when the clock falls back (03:00 CEST -> 02:00 CET in Madrid), the stored
    // values must keep growing, or ORDER BY created_at would return a row before the real last one
    @Test
    void createdAtKeepsGrowingInLocalTimeWhenTheClockFallsBack() {
        ZoneId madrid = ZoneId.of("Europe/Madrid");
        MutableClock dstClock = new MutableClock(Instant.parse("2026-10-25T00:59:59.999998Z"), madrid);
        dstClock.ticks(Duration.ofNanos(1_000));
        AuditChainBatchWriter dstWriter = new AuditChainBatchWriter(reader, statements, objectMapper, transactionManager,
                new AuditProperties(500), new ReactiveMutex(Schedulers.immediate()), dstClock);

        StepVerifier.create(dstWriter.append(PROCESS_ID, drafts(4), SOURCE)).verifyComplete();

        // 02:59:59.999998 CEST, 02:59:59.999999 CEST, then 02:00:00 CET on the clock: kept after the previous row
        assertThat(stored).extracting(row -> row.getCreatedAt().toLocalDateTime())
                .containsExactly(LocalDateTime.parse("2026-10-25T02:59:59.999998"),
                        LocalDateTime.parse("2026-10-25T02:59:59.999999"),
                        LocalDateTime.parse("2026-10-25T03:00:00"),
                        LocalDateTime.parse("2026-10-25T03:00:00.000001"));
    }

    @Test
    void theHashUsesTheSameSerializationAsASingleSave() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        AuditRecord auditRecord = AuditRecord.builder()
                .id(id)
                .processId("p")
                .createdAt(Timestamp.from(Instant.parse("2026-01-01T00:00:00.123456Z")))
                .entityId("urn:e:1")
                .entityType("product-offering")
                .entityHash("eh")
                .entityHashLink("ehl")
                .dataLocation("")
                .status(AuditRecordStatus.RETRIEVED)
                .trader(AuditRecordTrader.CONSUMER)
                .hash("")
                .hashLink("")
                .newTransaction(true)
                .build();

        // createdAt as epoch millis (the microseconds are not hashed), properties sorted, new and newTransaction included
        assertThat(objectMapper.writeValueAsString(auditRecord)).isEqualTo("{\"createdAt\":1767225600123,"
                + "\"dataLocation\":\"\",\"entityHash\":\"eh\",\"entityHashLink\":\"ehl\",\"entityId\":\"urn:e:1\","
                + "\"entityType\":\"product-offering\",\"hash\":\"\",\"hashLink\":\"\","
                + "\"id\":\"00000000-0000-0000-0000-000000000001\",\"new\":true,\"newTransaction\":true,"
                + "\"processId\":\"p\",\"status\":\"RETRIEVED\",\"trader\":\"CONSUMER\"}");

        clock.set(Instant.parse("2026-01-01T00:00:00.123456Z"));
        AuditRecord draft = auditRecord.toBuilder().createdAt(null).hash(null).hashLink(null).build();
        StepVerifier.create(writer(500).append(PROCESS_ID, List.of(draft), SOURCE)).verifyComplete();

        assertThat(stored.get(0).getHash())
                .isEqualTo(ApplicationUtils.calculateSHA256(objectMapper.writeValueAsString(auditRecord)));
    }

    @Test
    void itShouldSplitTheListInChunksChainedOnEachOther() throws Exception {
        StepVerifier.create(writer(500).append(PROCESS_ID, drafts(1200), SOURCE)).verifyComplete();

        assertThat(transactionManager.begins).hasValue(3);
        assertThat(transactionManager.commits).hasValue(3);
        verify(statements, times(3)).lockChain();
        verify(reader, times(3)).fetch();
        assertThat(stored).hasSize(1200);
        assertLinearChain(null, stored);
        assertStrictlyIncreasing(stored);
    }

    @Test
    void itShouldTakeTheLockAndReadTheLastRecordInsideTheTransactionBeforeInserting() {
        StepVerifier.create(writer(500).append(PROCESS_ID, drafts(2), SOURCE)).verifyComplete();

        var inOrder = inOrder(statements, reader);
        inOrder.verify(statements).lockChain();
        inOrder.verify(reader).fetch();
        inOrder.verify(statements).insert(any());
        assertThat(transactionManager.definition.get().getIsolationLevel())
                .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Test
    void anEmptyListOpensNoTransactionAndTakesNoLock() {
        StepVerifier.create(writer(500).append(PROCESS_ID, List.of(), SOURCE)).verifyComplete();

        assertThat(transactionManager.begins).hasValue(0);
        verifyNoInteractions(statements, reader);
    }

    @Test
    void aHashFailureWritesNothingOfTheChunk() throws Exception {
        ObjectMapper failingMapper = spy(objectMapper);
        doCallRealMethod().doCallRealMethod().doThrow(new JsonProcessingException("boom") {
        }).when(failingMapper).writeValueAsString(any());

        try (LogCapture logs = LogCapture.of(AuditChainBatchWriter.class)) {
            StepVerifier.create(writer(500, failingMapper).append(PROCESS_ID, drafts(5), SOURCE))
                    .expectError(JsonProcessingException.class)
                    .verify();

            assertThat(logs.messages(Level.INFO)).isEmpty();
        }
        assertThat(stored).isEmpty();
        verify(statements, never()).insert(any());
        assertThat(transactionManager.rollbacks).hasValue(1);
    }

    @Test
    void itShouldLogOneAuditLinePerRecordAfterTheCommit() {
        List<AuditRecord> drafts = drafts(2);

        try (LogCapture logs = LogCapture.of(AuditChainBatchWriter.class)) {
            StepVerifier.create(writer(500).append(PROCESS_ID, drafts, SOURCE)).verifyComplete();

            assertThat(logs.messages(Level.INFO)).containsExactly(
                    "[audit] ProcessID: process-1 - product-offering urn:e:0 -> RETRIEVED CONSUMER (source=p2p sync, entityHash=entity-hash-)",
                    "[audit] ProcessID: process-1 - product-offering urn:e:1 -> RETRIEVED CONSUMER (source=p2p sync, entityHash=entity-hash-)");
        }
    }

    @Test
    void aChunkThatRollsBackLeavesNoAuditLines() {
        insertBehaviour = rows -> Mono.error(connectionError());

        try (LogCapture logs = LogCapture.of(AuditChainBatchWriter.class)) {
            StepVerifier.create(writer(500).append(PROCESS_ID, drafts(3), SOURCE))
                    .expectError(DataAccessResourceFailureException.class)
                    .verify();

            assertThat(logs.messages(Level.INFO)).isEmpty();
        }
        assertThat(transactionManager.rollbacks).hasValue(1);
    }

    @Test
    void aRowDataErrorWritesTheChunkRowByRowUpToTheFailingRow() throws Exception {
        List<AuditRecord> drafts = drafts(10);
        String badId = drafts.get(4).getEntityId();
        BadSqlGrammarException batchError = dataError("22001");
        BadSqlGrammarException rowError = dataError("22001");
        insertBehaviour = rows -> rows.stream().anyMatch(row -> row.getEntityId().equals(badId))
                ? Mono.error(rows.size() > 1 ? batchError : rowError)
                : Mono.empty();

        try (LogCapture logs = LogCapture.of(AuditChainBatchWriter.class)) {
            StepVerifier.create(writer(500).append(PROCESS_ID, drafts, SOURCE))
                    .expectErrorMatches(rowError::equals)
                    .verify();

            assertThat(logs.messages(Level.WARN)).singleElement().asString()
                    .contains("Batch of 10 audit records failed (SQLSTATE 22001");
            assertThat(logs.messages(Level.ERROR)).isEmpty();
        }
        assertThat(stored).extracting(AuditRecord::getEntityId)
                .containsExactlyElementsOf(drafts.subList(0, 4).stream().map(AuditRecord::getEntityId).toList());
        assertLinearChain(null, stored);
    }

    @Test
    void theRowByRowPathStartsFromCleanDrafts() throws Exception {
        List<AuditRecord> drafts = drafts(3);
        AtomicInteger attempts = new AtomicInteger();
        insertBehaviour = rows -> rows.size() > 1 && attempts.getAndIncrement() == 0
                ? Mono.error(new DataIntegrityViolationException("dup", new R2dbcDataIntegrityViolationException("dup", "23505")))
                : Mono.empty();

        StepVerifier.create(writer(500).append(PROCESS_ID, drafts, SOURCE))
                .expectError(DataIntegrityViolationException.class)
                .verify();

        assertThat(stored).hasSize(3);
        for (AuditRecord row : stored) {
            AuditRecord unhashed = row.toBuilder().hash("").hashLink("").build();
            assertThat(row.getHash()).isEqualTo(ApplicationUtils.calculateSHA256(objectMapper.writeValueAsString(unhashed)));
        }
        assertLinearChain(null, stored);
    }

    @Test
    void whenNoRowFailsOnItsOwnItShouldReportTheBatchError() {
        BadSqlGrammarException batchError = dataError("22P02");
        insertBehaviour = rows -> rows.size() > 1 ? Mono.error(batchError) : Mono.empty();

        try (LogCapture logs = LogCapture.of(AuditChainBatchWriter.class)) {
            StepVerifier.create(writer(500).append(PROCESS_ID, drafts(4), SOURCE))
                    .expectErrorMatches(batchError::equals)
                    .verify();

            assertThat(logs.messages(Level.WARN)).hasSize(1);
            assertThat(logs.messages(Level.ERROR)).singleElement().asString()
                    .contains("every row was written on its own");
        }
        assertThat(stored).hasSize(4);
    }

    @Test
    void theChunksAfterAFailingOneAreNotWritten() {
        List<AuditRecord> drafts = drafts(12);
        String badId = drafts.get(6).getEntityId();
        insertBehaviour = rows -> rows.stream().anyMatch(row -> row.getEntityId().equals(badId))
                ? Mono.error(dataError("22001"))
                : Mono.empty();

        StepVerifier.create(writer(5).append(PROCESS_ID, drafts, SOURCE))
                .expectError(BadSqlGrammarException.class)
                .verify();

        assertThat(stored).hasSize(6);
    }

    @Test
    void otherDatabaseErrorsAreNotRetriedRowByRow() {
        insertBehaviour = rows -> Mono.error(connectionError());

        StepVerifier.create(writer(500).append(PROCESS_ID, drafts(3), SOURCE))
                .expectError(DataAccessResourceFailureException.class)
                .verify();

        verify(statements, times(1)).insert(any());
    }

    @Test
    void isRowDataErrorOnlyMatchesSqlStateClasses22And23() {
        assertThat(AuditChainBatchWriter.isRowDataError(dataError("22001"))).isTrue();
        assertThat(AuditChainBatchWriter.isRowDataError(
                new DataIntegrityViolationException("x", new R2dbcDataIntegrityViolationException("x", "23505")))).isTrue();
        assertThat(AuditChainBatchWriter.isRowDataError(
                new BadSqlGrammarException("x", "sql", new R2dbcBadGrammarException("x", "42601")))).isFalse();
        assertThat(AuditChainBatchWriter.isRowDataError(
                new DataIntegrityViolationException("x", new R2dbcDataIntegrityViolationException("x", "27000")))).isFalse();
        assertThat(AuditChainBatchWriter.isRowDataError(new DataIntegrityViolationException("x"))).isFalse();
        assertThat(AuditChainBatchWriter.isRowDataError(
                new DataIntegrityViolationException("x", new R2dbcDataIntegrityViolationException("x")))).isFalse();
        assertThat(AuditChainBatchWriter.isRowDataError(connectionError())).isFalse();
        assertThat(AuditChainBatchWriter.isRowDataError(new IllegalStateException("x"))).isFalse();
    }

    @Test
    void concurrentAppendsNeverHaveTwoTransactionsOpen() {
        AuditChainBatchWriter writer = new AuditChainBatchWriter(reader, statements, objectMapper, transactionManager,
                new AuditProperties(10), new ReactiveMutex(), clock);
        insertBehaviour = rows -> Mono.delay(Duration.ofMillis(2)).then();

        StepVerifier.create(Flux.range(0, 8)
                        .flatMap(i -> writer.append(PROCESS_ID, drafts(25), SOURCE).subscribeOn(Schedulers.parallel()))
                        .then())
                .expectComplete()
                .verify(Duration.ofSeconds(20));

        assertThat(transactionManager.maxOpen).hasValue(1);
        assertThat(stored).hasSize(200);
        assertStrictlyIncreasing(stored);
    }

    @Test
    void thePermitIsReleasedAfterAnErrorAndACancellation() {
        AuditChainBatchWriter writer = writer(500);
        insertBehaviour = rows -> Mono.error(connectionError());
        StepVerifier.create(writer.append(PROCESS_ID, drafts(2), SOURCE))
                .expectError(DataAccessResourceFailureException.class)
                .verify();

        insertBehaviour = rows -> Mono.never();
        Disposable hanging = writer.append(PROCESS_ID, drafts(2), SOURCE).subscribe();
        hanging.dispose();

        insertBehaviour = rows -> Mono.empty();
        StepVerifier.create(writer.append(PROCESS_ID, drafts(2), SOURCE))
                .expectComplete()
                .verify(Duration.ofSeconds(5));
        assertThat(stored).hasSize(2);
    }

    // --- helpers ---

    private static List<AuditRecord> drafts(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> AuditRecord.builder()
                        .id(UUID.randomUUID())
                        .processId(PROCESS_ID)
                        .entityId("urn:e:" + i)
                        .entityType("product-offering")
                        .entityHash("entity-hash-" + i)
                        .entityHashLink("entity-hashlink-" + i)
                        .dataLocation("")
                        .status(AuditRecordStatus.RETRIEVED)
                        .trader(AuditRecordTrader.CONSUMER)
                        .newTransaction(true)
                        .build())
                .toList();
    }

    private void assertLinearChain(String previousHashLink, List<AuditRecord> rows) throws Exception {
        String previous = previousHashLink;
        for (AuditRecord row : rows) {
            AuditRecord unhashed = row.toBuilder().hash("").hashLink("").build();
            String hash = ApplicationUtils.calculateSHA256(objectMapper.writeValueAsString(unhashed));
            assertThat(row.getHash()).isEqualTo(hash);
            assertThat(row.getHashLink()).isEqualTo(previous == null ? hash : ApplicationUtils.calculateHashLink(previous, hash));
            previous = row.getHashLink();
        }
    }

    private static void assertStrictlyIncreasing(List<AuditRecord> rows) {
        for (int i = 1; i < rows.size(); i++) {
            assertThat(rows.get(i).getCreatedAt()).isAfter(rows.get(i - 1).getCreatedAt());
        }
    }

    private static BadSqlGrammarException dataError(String sqlState) {
        return new BadSqlGrammarException("insert", "INSERT ...", new R2dbcBadGrammarException("value too long", sqlState));
    }

    private static DataAccessResourceFailureException connectionError() {
        return new DataAccessResourceFailureException("connection lost",
                new R2dbcNonTransientResourceException("connection lost", "08006"));
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        private volatile Duration tick = Duration.ZERO;

        private final ZoneId zone;

        MutableClock(Instant start) {
            this(start, ZoneId.systemDefault());
        }

        MutableClock(Instant start, ZoneId zone) {
            this.now = new AtomicReference<>(start);
            this.zone = zone;
        }

        void set(Instant instant) {
            now.set(instant);
        }

        void ticks(Duration tick) {
            this.tick = tick;
        }

        @Override
        public Instant instant() {
            return now.getAndUpdate(current -> current.plus(tick));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private static final class FakeTransactionManager implements ReactiveTransactionManager {
        final AtomicInteger begins = new AtomicInteger();
        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger rollbacks = new AtomicInteger();
        final AtomicInteger open = new AtomicInteger();
        final AtomicInteger maxOpen = new AtomicInteger();
        final AtomicReference<TransactionDefinition> definition = new AtomicReference<>();

        @Override
        public Mono<ReactiveTransaction> getReactiveTransaction(TransactionDefinition definition) {
            return Mono.fromSupplier(() -> {
                this.definition.set(definition);
                begins.incrementAndGet();
                maxOpen.accumulateAndGet(open.incrementAndGet(), Math::max);
                return new FakeTransaction();
            });
        }

        @Override
        public Mono<Void> commit(ReactiveTransaction transaction) {
            return Mono.fromRunnable(() -> {
                commits.incrementAndGet();
                open.decrementAndGet();
                ((FakeTransaction) transaction).completed = true;
            });
        }

        @Override
        public Mono<Void> rollback(ReactiveTransaction transaction) {
            return Mono.fromRunnable(() -> {
                rollbacks.incrementAndGet();
                open.decrementAndGet();
                ((FakeTransaction) transaction).completed = true;
            });
        }
    }

    private static final class FakeTransaction implements ReactiveTransaction {
        private boolean rollbackOnly;
        private volatile boolean completed;

        @Override
        public boolean isNewTransaction() {
            return true;
        }

        @Override
        public void setRollbackOnly() {
            rollbackOnly = true;
        }

        @Override
        public boolean isRollbackOnly() {
            return rollbackOnly;
        }

        @Override
        public boolean isCompleted() {
            return completed;
        }
    }
}
