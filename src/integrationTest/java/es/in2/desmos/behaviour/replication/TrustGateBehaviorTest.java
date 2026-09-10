package es.in2.desmos.behaviour.replication;

import es.in2.desmos.domain.models.BlockchainNotification;
import es.in2.desmos.support.NodeBProbe;
import es.in2.desmos.support.ReplicationFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R12 -- the trust gate. {@code BlockchainListenerServiceImpl.checkIfParticipantExistsInTrustedList}
 * runs synchronously, as part of the very same reactive chain the HTTP POST itself completes on,
 * before any audit record is ever written. A DLT notification from a sender outside the trusted
 * list should therefore be rejected outright (401) with zero side effects, not silently dropped
 * or partially processed -- and since the check happens before any entity-specific field is used,
 * this doesn't need a real notarized entity to exercise.
 */
class TrustGateBehaviorTest extends AbstractReplicationBehaviorTest {

    @Test
    void dltNotificationFromAnUntrustedSenderIsRejectedWithNoAuditTrail() {
        String entityId = "urn:ngsi-ld:category:" + UUID.randomUUID();
        BlockchainNotification notification = ReplicationFixtures.untrustedDltNotification(entityId);

        int status = NodeBProbe.postDltNotificationForStatus(notification);

        assertThat(status).as("untrusted sender should be rejected").isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(NodeBProbe.auditRowsFor(entityId))
                .as("no audit record should ever be written for a notification that fails the trust check")
                .isEmpty();
    }
}
