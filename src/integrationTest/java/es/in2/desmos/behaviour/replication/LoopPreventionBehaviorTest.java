package es.in2.desmos.behaviour.replication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.models.BlockchainNotification;
import es.in2.desmos.domain.repositories.AuditRecordRepository;
import es.in2.desmos.domain.utils.ApplicationUtils;
import es.in2.desmos.infrastructure.controllers.NotificationController;
import es.in2.desmos.support.ContainerManager;
import es.in2.desmos.support.NodeANotarizer;
import es.in2.desmos.support.NodeBProbe;
import es.in2.desmos.support.ReplicationFixtures;
import es.in2.desmos.support.ScorpioProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * R9 -- loop prevention. Once an entity delivered via DLT notification lands in node B's Scorpio,
 * node B's own broker subscription fires a notification straight back at node B (its
 * {@code NGSI_SUBSCRIPTION_NOTIFICATION_ENDPOINT} points at itself). That notification is
 * indistinguishable, on the wire, from a genuine local change -- so
 * {@code BrokerListenerServiceImpl.isBrokerNotificationSelfGenerated} is what's supposed to stop
 * node B from re-notarizing (as trader PRODUCER) an entity it only ever consumed, by comparing
 * the incoming notification's hash against the {@code entityHash} of the most recent
 * RETRIEVED/DELETED audit record for that entity.
 * <p>
 * Those two hashes come from different serializations of the same entity (Scorpio's own
 * self-notification payload vs. the body node B originally fetched from node A) -- exactly the
 * mismatch class that has already caused real bugs in this codebase. If this test fails, treat it
 * as a likely genuine replication-loop defect, not a broken test -- do not weaken the assertion to
 * make it pass.
 */
class LoopPreventionBehaviorTest extends AbstractReplicationBehaviorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private NotificationController notificationController;

    @Autowired
    private AuditRecordRepository auditRecordRepository;

    @AfterEach
    void cleanUp() {
        auditRecordRepository.deleteAll().block();
    }

    @Test
    void entityReplicatedViaDltNeverAccruesProducerRowsOnNodeB() throws JsonProcessingException {
        String entityId = "urn:ngsi-ld:category:" + UUID.randomUUID();
        AuditRecord published = NodeANotarizer.seedAndNotarizeCategoryEntity(notificationController, auditRecordRepository, entityId);
        BlockchainNotification notification = ReplicationFixtures.dltNotificationFrom(published, entityId);

        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(3))
                .ignoreExceptions()
                .untilAsserted(() -> {
                    NodeBProbe.postDltNotification(notification);
                    String actual = NodeBProbe.getEntityFromBrokerOrNull(entityId);
                    assertThat(actual)
                            .as("entity should have been upserted into node B's broker")
                            .isNotNull()
                            .contains(entityId);
                });

        // Node B's own broker subscription has had time to fire its self-notification by now
        // (the upsert above already triggered it); hold this window to catch a delayed loop, not
        // just an immediate one.
        await().pollInterval(Duration.ofSeconds(2))
                .during(Duration.ofSeconds(20))
                .atMost(Duration.ofSeconds(25))
                .untilAsserted(() -> assertThat(NodeBProbe.auditRowsFor(entityId))
                        .as("node B should never re-notarize a DLT-delivered entity as its own PRODUCER")
                        .allSatisfy(row -> assertThat(row.trader()).isEqualToIgnoringCase("CONSUMER")));
    }

    /**
     * A subentity of a subscribed type, written by node B with the root of a DLT event, echoes back
     * to node B through its own broker subscription like the root does. Unless node B recorded what
     * it wrote, it takes that echo for a local change and re-notarizes the subentity as PRODUCER.
     */
    @Test
    void subscribedSubentityReplicatedViaDltNeverAccruesProducerRowsOnNodeB() throws JsonProcessingException {
        String entityId = "urn:ngsi-ld:product-offering:" + UUID.randomUUID();
        String subentityId = "urn:ngsi-ld:product-offering-price:" + UUID.randomUUID();
        deliverViaDlt(entityId, subentityId, ReplicationFixtures.productOfferingPriceEntityJson(subentityId));

        // Same window as for the root: catches a delayed echo, not just an immediate one
        await().pollInterval(Duration.ofSeconds(2))
                .during(Duration.ofSeconds(20))
                .atMost(Duration.ofSeconds(25))
                .untilAsserted(() -> {
                    List<NodeBProbe.AuditRow> rows = NodeBProbe.auditRowsFor(subentityId);
                    assertThat(rows)
                            .as("node B should never re-notarize a subentity delivered with a DLT event as its own PRODUCER\n%s",
                                    echoDiagnostics(subentityId, rows))
                            .allSatisfy(row -> assertThat(row.trader()).isEqualToIgnoringCase("CONSUMER"));
                });
    }

    /**
     * A subentity of a type outside the broker subscription never echoes back, so the absence of
     * PRODUCER rows proves nothing about loop prevention here. What this checks is that node B still
     * records what it wrote: a RETRIEVED/CONSUMER row with the hash of the written body, which is
     * what would stop the echo if the type were ever subscribed.
     */
    @Test
    void unsubscribedSubentityReplicatedViaDltIsRecordedAsRetrievedOnNodeB() throws JsonProcessingException {
        String entityId = "urn:ngsi-ld:product-offering:" + UUID.randomUUID();
        String subentityId = "urn:ngsi-ld:other:" + UUID.randomUUID();
        deliverViaDlt(entityId, subentityId, ReplicationFixtures.unsubscribedSubentityJson(subentityId));

        String writtenHash = sha256(writtenBody(subentityId));
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    List<NodeBProbe.AuditRow> rows = NodeBProbe.auditRowsFor(subentityId);
                    assertThat(rows)
                            .as("node B should record the subentity it wrote as RETRIEVED/CONSUMER with the hash of the written body\n%s",
                                    echoDiagnostics(subentityId, rows))
                            .anySatisfy(row -> {
                                assertThat(row.status()).isEqualTo("RETRIEVED");
                                assertThat(row.trader()).isEqualToIgnoringCase("CONSUMER");
                                assertThat(row.entityHash()).isEqualTo(writtenHash);
                            })
                            .noneSatisfy(row -> assertThat(row.trader()).isEqualToIgnoringCase("PRODUCER"));
                });
    }

    // Seeds the root with its subentity on node A, notarizes the root and delivers its DLT event to node B, until
    // both entities are in node B's broker
    private void deliverViaDlt(String entityId, String subentityId, String subentityJson) throws JsonProcessingException {
        AuditRecord published = NodeANotarizer.seedAndNotarize(notificationController, auditRecordRepository, entityId,
                List.of(subentityJson, ReplicationFixtures.productOfferingWithSubentityJson(entityId, subentityId)));
        BlockchainNotification notification = ReplicationFixtures.dltNotificationFrom(published, entityId);

        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(3))
                .ignoreExceptions()
                .untilAsserted(() -> {
                    NodeBProbe.postDltNotification(notification);
                    assertThat(NodeBProbe.getEntityFromBrokerOrNull(entityId))
                            .as("root should have been upserted into node B's broker")
                            .isNotNull();
                    assertThat(NodeBProbe.getEntityFromBrokerOrNull(subentityId))
                            .as("subentity should have been upserted into node B's broker")
                            .isNotNull();
                });
    }

    // The body node B wrote: node A serves the subentity as its broker returns it
    private static String writtenBody(String subentityId) {
        return ScorpioProbe.getEntity(ContainerManager.getBaseUriForScorpioA(), subentityId);
    }

    // If the hashes differ, the echo check cannot match: the written body and what node B's broker notifies are
    // serialized differently (an attribute added or dropped by Scorpio). Node B's stored entity stands in for the
    // notification payload, hashed as BrokerListenerServiceImpl hashes it (a Jackson round trip through a Map).
    @SuppressWarnings("unchecked")
    private static String echoDiagnostics(String subentityId, List<NodeBProbe.AuditRow> rows) {
        try {
            String written = writtenBody(subentityId);
            String stored = NodeBProbe.getEntityFromBrokerOrNull(subentityId);
            String storedHash = stored == null ? null
                    : sha256(OBJECT_MAPPER.writer().writeValueAsString(OBJECT_MAPPER.readValue(stored, Map.class)));
            return "written body (node A): " + written + "\n  hash: " + sha256(written)
                    + "\nstored body (node B, as notified): " + stored + "\n  hash: " + storedHash
                    + "\nnode B audit rows: " + rows;
        } catch (RuntimeException | JsonProcessingException e) {
            return "diagnostics unavailable: " + e;
        }
    }

    private static String sha256(String value) {
        try {
            return ApplicationUtils.calculateSHA256(value);
        } catch (java.security.NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
