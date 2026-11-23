package es.in2.desmos.domain.services.api.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.models.AuditRecordStatus;
import es.in2.desmos.domain.models.AuditRecordTrader;
import es.in2.desmos.domain.models.BlockchainTxPayload;
import es.in2.desmos.domain.models.ImplicitRetrievedEntity;
import es.in2.desmos.domain.models.MVAuditServiceEntity4DataNegotiation;
import es.in2.desmos.domain.models.MVEntity4DataNegotiation;
import es.in2.desmos.domain.repositories.AuditRecordRepository;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.domain.utils.ApplicationUtils;
import es.in2.desmos.inflators.ScorpioInflator;
import es.in2.desmos.support.ContainerManager;
import es.in2.desmos.support.ScorpioProbe;
import es.in2.desmos.objectmothers.AuditRecordMother;
import es.in2.desmos.objectmothers.MVAuditServiceEntity4DataNegotiationMother;
import es.in2.desmos.objectmothers.MVEntity4DataNegotiationMother;
import org.json.JSONException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditRecordServiceIT {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    AuditRecordService auditRecordService;

    @Autowired
    LatestAuditRecordReader latestAuditRecordReader;

    @Autowired
    AuditRecordRepository auditRecordRepository;

    @DynamicPropertySource
    private static void setDynamicProperties(DynamicPropertyRegistry registry) {
        ContainerManager.postgresqlProperties(registry);
    }

    @BeforeEach
    void setUp() {
        InitializeAuditRecord(latestAuditRecordReader);
    }

    @Test
    void itShouldBuildAndSaveAuditRecordsFromDataSync() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String processId = "0";
        MVAuditServiceEntity4DataNegotiation mvAuditServiceEntity4DataNegotiationMother = MVAuditServiceEntity4DataNegotiationMother.sample1();
        AuditRecordStatus status = AuditRecordStatus.RETRIEVED;

        Mono<Void> result = auditRecordService.buildAndSaveAuditRecordsFromDataSync(processId, List.of(mvAuditServiceEntity4DataNegotiationMother), status);

        StepVerifier
                .create(result)
                .verifyComplete();

        assertAuditRecordEntityIsExpected(mvAuditServiceEntity4DataNegotiationMother.id(), MVEntity4DataNegotiationMother.sample1());
    }

    @Test
    void itShouldRecordAnImplicitRetrievedOnceAndLeaveTheProducerHashLinkUntouched() {
        String processId = "0";
        String entityId = "urn:ngsi-ld:product-offering-price:" + UUID.randomUUID();
        String dataLocation = "http://node-a/api/v1/entities/" + entityId + "?hl=producerHashLink";
        Map<String, Object> dataMap = Map.of("id", entityId, "type", "product-offering-price");
        StepVerifier.create(auditRecordService.buildAndSaveAuditRecordFromBrokerNotification(processId, dataMap,
                        AuditRecordStatus.PUBLISHED, BlockchainTxPayload.builder().dataLocation(dataLocation).build()))
                .verifyComplete();
        List<ImplicitRetrievedEntity> entities = List.of(
                new ImplicitRetrievedEntity(entityId, "product-offering-price", "{\"id\":\"" + entityId + "\"}"));

        StepVerifier.create(auditRecordService.saveImplicitConsumerRetrieved(processId, entities)
                        .then(auditRecordService.saveImplicitConsumerRetrieved(processId, entities)))
                .expectNext(0)
                .verifyComplete();

        StepVerifier.create(auditRecordRepository.findByEntityId(entityId)
                        .filter(auditRecord -> auditRecord.getStatus() == AuditRecordStatus.RETRIEVED)
                        .collectList())
                .assertNext(retrieved -> assertThat(retrieved)
                        .as("the second call has the same hash and writes nothing")
                        .singleElement()
                        .satisfies(auditRecord -> assertThat(auditRecord.getTrader()).isEqualTo(AuditRecordTrader.CONSUMER)))
                .verifyComplete();
        StepVerifier.create(auditRecordService.fetchLatestProducerEntityHashLinkByEntityId(processId, entityId))
                .expectNext("producerHashLink")
                .verifyComplete();
    }

    // The 2:00 guard (P2P discovery of the local entities). The entities are of a type outside ROOT_OBJECTS_LIST so the
    // startup synchronization of this context, which lists the local entities of those types, never touches them.
    @Test
    void itShouldAnnounceAnEntityWithOnlyAConsumerRetrievedRecordWithoutWritingAnyRecord() throws Exception {
        String processId = "0";
        String entityId = "urn:ngsi-ld:other:" + UUID.randomUUID();
        String body = seedInBroker(entityId);
        StepVerifier.create(auditRecordService.saveImplicitConsumerRetrieved(processId,
                        List.of(new ImplicitRetrievedEntity(entityId, "other", body))))
                .expectNext(1)
                .verifyComplete();
        long rowsBefore = countRows(entityId);
        String brokerHash = sha256(body);

        StepVerifier.create(auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(processId, "other", Flux.just(entityId)))
                .assertNext(announced -> {
                    assertThat(announced.id()).isEqualTo(entityId);
                    assertThat(announced.hash()).isEqualTo(brokerHash);
                    assertThat(announced.hashlink()).isEqualTo(brokerHash);
                })
                .verifyComplete();

        assertThat(countRows(entityId)).as("no PRODUCER record for an entity retrieved from another node").isEqualTo(rowsBefore);
    }

    @Test
    void itShouldKeepAnnouncingTheHashLinkOfAnEntityWithAPublishedRecord() throws Exception {
        String processId = "0";
        String entityId = "urn:ngsi-ld:other:" + UUID.randomUUID();
        String body = seedInBroker(entityId);
        // Published with the hash of the current broker body, so it is not outdated
        @SuppressWarnings("unchecked")
        Map<String, Object> dataMap = OBJECT_MAPPER.readValue(body, Map.class);
        StepVerifier.create(auditRecordService.buildAndSaveAuditRecordFromBrokerNotification(processId, dataMap,
                        AuditRecordStatus.PUBLISHED, BlockchainTxPayload.builder()
                                .dataLocation("http://node-a/api/v1/entities/" + entityId + "?hl=publishedHashLink").build()))
                .verifyComplete();
        StepVerifier.create(auditRecordService.saveImplicitConsumerRetrieved(processId,
                        List.of(new ImplicitRetrievedEntity(entityId, "other", body))))
                .expectNext(1)
                .verifyComplete();
        long rowsBefore = countRows(entityId);

        StepVerifier.create(auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(processId, "other", Flux.just(entityId)))
                .assertNext(announced -> assertThat(announced.hashlink()).isEqualTo("publishedHashLink"))
                .verifyComplete();

        assertThat(countRows(entityId)).isEqualTo(rowsBefore);
    }

    private static String seedInBroker(String entityId) {
        ScorpioInflator.addEntitiesToBroker(ContainerManager.getBaseUriForScorpioA(),
                "[{\"id\":\"" + entityId + "\",\"type\":\"other\",\"name\":{\"type\":\"Property\",\"value\":\"guard\"}}]");
        return ScorpioProbe.getEntity(ContainerManager.getBaseUriForScorpioA(), entityId);
    }

    private long countRows(String entityId) {
        return auditRecordRepository.findByEntityId(entityId).count().block();
    }

    private static String sha256(String value) {
        try {
            return ApplicationUtils.calculateSHA256(value);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertAuditRecordEntityIsExpected(String entityId, MVEntity4DataNegotiation expectedMVEntity4DataNegotiation) {
        await().atMost(10, TimeUnit.SECONDS).ignoreExceptions().until(() -> {
            String processId = "0";
            Mono<AuditRecord> auditRecordMono = auditRecordService.findMostRecentRetrievedOrDeletedByEntityId(processId, entityId);

            StepVerifier
                    .create(auditRecordMono)
                    .consumeNextWith(auditRecord -> {
                        System.out.println("Entity audit record AuditRecord entity check: " + auditRecord);

                        AuditRecord expectedAuditRecord = AuditRecordMother.createAuditRecordFromMVEntity4DataNegotiation(expectedMVEntity4DataNegotiation, AuditRecordStatus.RETRIEVED);

                        assertThat(auditRecord)
                                .usingRecursiveComparison()
                                .ignoringFields("processId", "id", "createdAt", "hash", "hashLink", "newTransaction")
                                .isEqualTo(expectedAuditRecord);
                    })
                    .expectComplete()
                    .verify();

            return true;
        });
    }

    private void InitializeAuditRecord(LatestAuditRecordReader latestAuditRecordReader) {
        latestAuditRecordReader.fetch().block();
    }
}
