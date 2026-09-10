package es.in2.desmos.behaviour.replication;

import com.fasterxml.jackson.core.JsonProcessingException;
import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.models.BlockchainNotification;
import es.in2.desmos.domain.repositories.AuditRecordRepository;
import es.in2.desmos.infrastructure.controllers.NotificationController;
import es.in2.desmos.support.NodeANotarizer;
import es.in2.desmos.support.NodeBProbe;
import es.in2.desmos.support.ReplicationAssertions;
import es.in2.desmos.support.ReplicationFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The consumer side of replication, the other half of the picture {@link PublishFlowBehaviorTest}
 * covers: an entity is seeded into node A's Scorpio and then notarized for real (so its
 * dataLocation, hash and hashlink are exactly what the production code computed, not
 * hand-derived by this test, via {@link NodeANotarizer}), then a synthetic
 * {@link BlockchainNotification} built from those real values is POSTed straight to node B's
 * public {@code /api/v2/notifications/dlt} endpoint -- standing in for the DLT adapter actually
 * delivering the event (the adapter in this harness points at an unreachable RPC node, so it
 * never fires organically; see ContainerManager). Node B should verify the notification's sender
 * against its trusted list, fetch the entity from node A (an authenticated
 * {@code GET /api/v2/entities/{id}}), verify its hash and hashlink, and upsert it into its own
 * Scorpio broker as trader CONSUMER.
 * <p>
 * The negative counterparts of this same arrangement -- an untrusted sender
 * ({@link TrustGateBehaviorTest}) and a tampered hashlink ({@link IntegrityBehaviorTest}) -- live
 * alongside this class.
 */
class SubscribeFlowBehaviorTest extends AbstractReplicationBehaviorTest {

    @Autowired
    private NotificationController notificationController;

    @Autowired
    private AuditRecordRepository auditRecordRepository;

    @AfterEach
    void cleanUp() {
        auditRecordRepository.deleteAll().block();
    }

    @Test
    void nodeBUpsertsAnEntityNotarizedByNodeAUponADltNotification() throws JsonProcessingException {
        String entityId = "urn:ngsi-ld:category:" + UUID.randomUUID();
        AuditRecord published = NodeANotarizer.seedAndNotarizeCategoryEntity(notificationController, auditRecordRepository, entityId);

        BlockchainNotification notification = ReplicationFixtures.dltNotificationFrom(published, entityId);

        // Same caveat as the publish side: node B's subscribe-queue consumer is only wired up
        // once ITS OWN startup sequence finishes, and the queue is a unicast sink -- a
        // notification delivered before that consumer subscribes is dropped for good. The
        // notification itself is deterministic (built from A's real, already-notarized
        // values), so it's safe to simply re-POST it on each attempt until one lands after
        // node B is actually ready.
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

        ReplicationAssertions.assertConsumerTrail(NodeBProbe.auditRowsFor(entityId));
    }
}
