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
 * R11 -- integrity. A tampered hashlink takes the "not first in chain" branch of
 * {@code DataSyncServiceImpl.verifyRetrievedEntityDataIntegrity}: the recomputed hashlink
 * mismatches, and {@code SubscribeWorkflowImpl}'s {@code onErrorResume} logs and swallows the
 * resulting {@code HashLinkException}, so the POST itself still returns 2xx. The only observable
 * effect of a failed integrity check is what *doesn't* happen -- assert on state, not on HTTP
 * status: the entity never lands in node B's broker, and its audit trail never leaves RECEIVED.
 */
class IntegrityBehaviorTest extends AbstractReplicationBehaviorTest {

    @Autowired
    private NotificationController notificationController;

    @Autowired
    private AuditRecordRepository auditRecordRepository;

    @AfterEach
    void cleanUp() {
        auditRecordRepository.deleteAll().block();
    }

    @Test
    void tamperedHashlinkNeverGetsUpsertedAndTrailNeverLeavesReceived() throws JsonProcessingException {
        String entityId = "urn:ngsi-ld:category:" + UUID.randomUUID();
        AuditRecord published = NodeANotarizer.seedAndNotarizeCategoryEntity(notificationController, auditRecordRepository, entityId);
        BlockchainNotification tampered = ReplicationFixtures.dltNotificationWithTamperedHashlink(published, entityId);

        // Node B's Testcontainers wait strategy already blocks container startup on "Queues have
        // been authorized and enabled", so by the time any test runs its subscribe-queue consumer
        // is ready -- but repost once more anyway, matching the happy path's caution. Reposting is
        // harmless here: every assertion below is about the trail never progressing, not about
        // how many RECEIVED rows precede that.
        NodeBProbe.postDltNotification(tampered);
        NodeBProbe.postDltNotification(tampered);

        await().pollInterval(Duration.ofSeconds(2))
                .during(Duration.ofSeconds(20))
                .atMost(Duration.ofSeconds(25))
                .untilAsserted(() -> {
                    assertThat(NodeBProbe.getEntityFromBrokerOrNull(entityId))
                            .as("entity should never be upserted when its hashlink fails integrity verification")
                            .isNull();
                    assertThat(NodeBProbe.auditRowsFor(entityId))
                            .as("trail should never progress past RECEIVED")
                            .allSatisfy(row -> assertThat(row.status()).isEqualTo("RECEIVED"));
                });
    }
}
