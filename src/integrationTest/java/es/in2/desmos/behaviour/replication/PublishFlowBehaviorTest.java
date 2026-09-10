package es.in2.desmos.behaviour.replication;

import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.repositories.AuditRecordRepository;
import es.in2.desmos.infrastructure.controllers.NotificationController;
import es.in2.desmos.support.QueueReadiness;
import es.in2.desmos.support.ReplicationAssertions;
import es.in2.desmos.support.ReplicationFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/**
 * The producer side of replication: a broker notification for a locally-owned entity should
 * be notarized -- audited RECEIVED, then CREATED (once a BlockchainTxPayload with a
 * dataLocation pointing back at this node exists), then PUBLISHED (once it's been sent to the
 * DLT adapter), all as trader PRODUCER. This is what a peer node's subscribe flow will later
 * fetch via that same dataLocation.
 * <p>
 * Runs in-process against node A only -- reusing {@link AbstractReplicationBehaviorTest} costs
 * nothing extra since the whole topology (including node B) is already up for the other
 * replication tests sharing this context, and it keeps this class independent of any
 * particular scenario ordering.
 */
class PublishFlowBehaviorTest extends AbstractReplicationBehaviorTest {

    @Autowired
    private NotificationController notificationController;

    @Autowired
    private AuditRecordRepository auditRecordRepository;

    @AfterEach
    void cleanUp() {
        auditRecordRepository.deleteAll().block();
    }

    @Test
    void publishingALocallyOwnedEntityNotarizesItAsProducer() {
        // ApplicationRunner only wires the publish queue's consumer up after node A's initial
        // P2P sync against node B completes, and QueueService is a unicast sink -- an event
        // posted before that consumer subscribes is dropped, permanently, no matter how long
        // this test then waits. Rather than guess at a fixed startup delay, post a fresh
        // notification (a fresh entity id, so a lost attempt never leaves stray RECEIVED rows
        // behind for the entity this test finally asserts on) and give it a short window to
        // progress past RECEIVED; if the consumer wasn't ready yet, that attempt's event is gone
        // for good, so retry with a new one.
        List<AuditRecord> trail = await()
                .atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofSeconds(3))
                .until(this::postFreshNotificationAndCheckProgress, ReplicationAssertions::hasReachedPublished);

        ReplicationAssertions.assertProducerTrail(trail);
    }

    private List<AuditRecord> postFreshNotificationAndCheckProgress() {
        String entityId = "urn:ngsi-ld:category:" + UUID.randomUUID();
        notificationController.postBrokerNotification(ReplicationFixtures.brokerNotificationForCategory(entityId))
                .block(Duration.ofSeconds(10));

        // A short, bounded window for this specific attempt to progress -- long enough for the
        // queue -> workflow -> DLT-adapter round trip once a consumer is actually attached,
        // short enough that a lost (pre-readiness) attempt doesn't eat the whole budget above.
        return QueueReadiness.pollWithPartialFallback(
                Duration.ofSeconds(8), Duration.ofMillis(500),
                () -> QueueReadiness.trailFor(auditRecordRepository, entityId),
                ReplicationAssertions::hasReachedPublished);
    }
}
