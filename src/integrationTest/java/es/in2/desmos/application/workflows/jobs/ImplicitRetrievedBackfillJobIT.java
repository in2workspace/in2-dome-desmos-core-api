package es.in2.desmos.application.workflows.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.models.AuditRecordStatus;
import es.in2.desmos.domain.models.AuditRecordTrader;
import es.in2.desmos.domain.models.BlockchainTxPayload;
import es.in2.desmos.domain.models.MVAuditServiceEntity4DataNegotiation;
import es.in2.desmos.domain.repositories.AuditRecordRepository;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import es.in2.desmos.domain.utils.ApplicationUtils;
import es.in2.desmos.inflators.ScorpioInflator;
import es.in2.desmos.support.ContainerManager;
import es.in2.desmos.support.ScorpioProbe;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backfill against the Scorpio and Postgres of node A. The runner is off and the web environment is MOCK, so no
 * startup synchronization and no broker notification writes audit records while the test runs (see
 * AuditRecordRepositoryIT). The database and the broker are shared with the other IT classes, so the assertions only
 * look at the entities of this test.
 */
@SpringBootTest(properties = "application.runner.enabled=false")
@Testcontainers
class ImplicitRetrievedBackfillJobIT {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PROCESS_ID = "backfill-it";

    @Autowired
    private ImplicitRetrievedBackfillJob implicitRetrievedBackfillJob;
    @Autowired
    private AuditRecordService auditRecordService;
    @Autowired
    private AuditRecordRepository auditRecordRepository;
    @Autowired
    private BrokerPublisherService brokerPublisherService;

    @DynamicPropertySource
    static void setDynamicProperties(DynamicPropertyRegistry registry) {
        ContainerManager.postgresqlProperties(registry);
    }

    @Test
    void itShouldRecordOnlyTheSubentitiesWhoseContentIsKnownAndWriteNothingTheSecondTime() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String rootId = "urn:ngsi-ld:product-offering:" + suffix;
        String knownChildId = "urn:ngsi-ld:product-offering-price:known-" + suffix;
        String editedChildId = "urn:ngsi-ld:product-offering-price:edited-" + suffix;
        ScorpioInflator.addEntitiesToBroker(ContainerManager.getBaseUriForScorpioA(), "["
                + price(knownChildId, "known") + ","
                + price(editedChildId, "before") + ","
                + "{\"id\":\"" + rootId + "\",\"type\":\"product-offering\","
                + "\"name\":{\"type\":\"Property\",\"value\":\"root\"},"
                + "\"productOfferingPrice\":{\"type\":\"Relationship\",\"object\":\"" + knownChildId + "\"},"
                + "\"bundledPrice\":{\"type\":\"Relationship\",\"object\":\"" + editedChildId + "\"}}]");
        String rootBody = ScorpioProbe.getEntity(ContainerManager.getBaseUriForScorpioA(), rootId);
        String knownChildBody = ScorpioProbe.getEntity(ContainerManager.getBaseUriForScorpioA(), knownChildId);
        String editedChildBefore = ScorpioProbe.getEntity(ContainerManager.getBaseUriForScorpioA(), editedChildId);

        // The root came from another node; both children were wrongly published as PRODUCER by the echo of their write
        String rootHash = ApplicationUtils.calculateSHA256(rootBody);
        auditRecordService.buildAndSaveAuditRecordsFromDataSync(PROCESS_ID,
                List.of(new MVAuditServiceEntity4DataNegotiation(rootId, "product-offering", rootHash, rootHash)),
                AuditRecordStatus.PUBLISHED).block();
        publishedAsProducer(knownChildBody);
        publishedAsProducer(editedChildBefore);
        // A local edit of the second child whose notification has not been processed yet
        brokerPublisherService.upsertDataInBroker(PROCESS_ID, editedChildId, price(editedChildId, "after")).block();

        implicitRetrievedBackfillJob.run(PROCESS_ID).block();

        assertThat(retrieved(knownChildId))
                .singleElement()
                .satisfies(auditRecord -> {
                    assertThat(auditRecord.getTrader()).isEqualTo(AuditRecordTrader.CONSUMER);
                    assertThat(auditRecord.getEntityHash()).isEqualTo(ApplicationUtils.calculateSHA256(knownChildBody));
                    assertThat(auditRecord.getEntityType()).isEqualTo("product-offering-price");
                });
        assertThat(retrieved(editedChildId)).as("unknown content: maybe a local edit").isEmpty();
        assertThat(retrieved(rootId)).as("a root is not a subentity").isEmpty();

        long rowsBefore = rows(knownChildId) + rows(editedChildId) + rows(rootId);
        implicitRetrievedBackfillJob.run(PROCESS_ID).block();
        assertThat(rows(knownChildId) + rows(editedChildId) + rows(rootId)).as("second run").isEqualTo(rowsBefore);
    }

    private void publishedAsProducer(String body) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> dataMap = OBJECT_MAPPER.readValue(body, Map.class);
        auditRecordService.buildAndSaveAuditRecordFromBrokerNotification(PROCESS_ID, dataMap, AuditRecordStatus.PUBLISHED,
                BlockchainTxPayload.builder().dataLocation("http://node-a/api/v1/entities/" + dataMap.get("id") + "?hl=x").build())
                .block();
    }

    private List<AuditRecord> retrieved(String entityId) {
        return auditRecordRepository.findByEntityId(entityId)
                .filter(auditRecord -> auditRecord.getStatus() == AuditRecordStatus.RETRIEVED)
                .collectList().block();
    }

    private long rows(String entityId) {
        return auditRecordRepository.findByEntityId(entityId).count().block();
    }

    private static String price(String id, String name) {
        return "{\"id\":\"" + id + "\",\"type\":\"product-offering-price\","
                + "\"name\":{\"type\":\"Property\",\"value\":\"" + name + "\"}}";
    }
}
