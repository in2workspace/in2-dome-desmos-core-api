package es.in2.desmos.behaviour.replication;

import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.models.BrokerNotification;
import es.in2.desmos.domain.repositories.AuditRecordRepository;
import es.in2.desmos.infrastructure.controllers.NotificationController;
import es.in2.desmos.objectmothers.EntityMother;
import es.in2.desmos.support.QueueReadiness;
import es.in2.desmos.support.ReplicationFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R10 -- global policy filtering, tested on the publish path: {@code BrokerListenerServiceImpl}
 * checks {@code ReplicationPoliciesServiceImpl} (GP_1 lifecycleStatus, GP_2 validFor) and returns
 * {@code Mono.empty()} *before* any audit record is written when an entity fails either policy --
 * so the assertion is simply "zero audit rows", checked synchronously right after the POST
 * completes: the policy check runs in the same reactive chain the HTTP request itself completes
 * on, with no queue-readiness race to work around (unlike the DLT-path scenarios).
 */
class PolicyFilteringBehaviorTest extends AbstractReplicationBehaviorTest {

    @Autowired
    private NotificationController notificationController;

    @Autowired
    private AuditRecordRepository auditRecordRepository;

    @AfterEach
    void cleanUp() {
        auditRecordRepository.deleteAll().block();
    }

    @Test
    void rejectsNonFreeTypeWithInvalidLifecycleStatus() {
        String entityId = "urn:ngsi-ld:category:" + UUID.randomUUID();
        post(ReplicationFixtures.rejectedByLifecycleStatusNotification(entityId));

        assertNoAuditTrail(entityId, "lifecycleStatus outside {Launched, Retired, Obsolete}");
    }

    @Test
    void rejectsNonFreeTypeWithNullLifecycleStatus() {
        post(ReplicationFixtures.brokerNotificationFromEntityJson(EntityMother.PRODUCT_OFFERING_1_NULL_LIFECYCLESTATUS));

        assertNoAuditTrail("urn:product-offering:d86735a6-0faa-463d-a872-00b97affa1cb", "missing lifecycleStatus on a non-free type");
    }

    @Test
    void rejectsEntityWithExpiredValidFor() {
        String entityId = "urn:ngsi-ld:category:" + UUID.randomUUID();
        post(ReplicationFixtures.expiredValidForNotification(entityId));

        assertNoAuditTrail(entityId, "validFor.endDateTime already in the past");
    }

    @Test
    void rejectsEntityWithFutureValidFor() {
        String entityId = "urn:ngsi-ld:category:" + UUID.randomUUID();
        post(ReplicationFixtures.futureValidForNotification(entityId));

        assertNoAuditTrail(entityId, "validFor.startDateTime not yet reached");
    }

    @Test
    void acceptsFreeTypeWithNoLifecycleStatus() {
        String entityId = "urn:ngsi-ld:quote:" + UUID.randomUUID();
        post(ReplicationFixtures.freeTypeWithNoLifecycleStatusNotification(entityId));

        List<AuditRecord> trail = QueueReadiness.trailFor(auditRecordRepository, entityId);
        assertThat(trail)
                .as("a LIFECYCLE_STATUS_FREE_TYPES type with no lifecycleStatus should clear GP_1 and be notarized")
                .isNotEmpty();
    }

    private void post(BrokerNotification notification) {
        notificationController.postBrokerNotification(notification).block(Duration.ofSeconds(10));
    }

    private void assertNoAuditTrail(String entityId, String reason) {
        assertThat(QueueReadiness.trailFor(auditRecordRepository, entityId))
                .as("no audit record should ever be written for an entity rejected for %s", reason)
                .isEmpty();
    }
}
