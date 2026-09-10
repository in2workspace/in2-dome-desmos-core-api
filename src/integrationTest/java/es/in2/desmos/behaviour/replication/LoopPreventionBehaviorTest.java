package es.in2.desmos.behaviour.replication;

import com.fasterxml.jackson.core.JsonProcessingException;
import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.models.BlockchainNotification;
import es.in2.desmos.domain.repositories.AuditRecordRepository;
import es.in2.desmos.infrastructure.controllers.NotificationController;
import es.in2.desmos.support.NodeANotarizer;
import es.in2.desmos.support.NodeBProbe;
import es.in2.desmos.support.ReplicationFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
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
}
