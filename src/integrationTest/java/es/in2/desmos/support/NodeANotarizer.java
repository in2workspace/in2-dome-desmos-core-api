package es.in2.desmos.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.models.BrokerNotification;
import es.in2.desmos.domain.repositories.AuditRecordRepository;
import es.in2.desmos.inflators.ScorpioInflator;
import es.in2.desmos.infrastructure.controllers.NotificationController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;

/**
 * The "seed an entity into node A's Scorpio, then publish it for real so its dataLocation, hash
 * and hashlink are exactly what production computed" arrangement every subscribe-path scenario
 * (the happy path, plus the trust-gate and integrity negative scenarios) starts from. Posts
 * Scorpio's own GET response for the entity as the notification payload -- not a hand-written
 * literal -- since node B's later hash verification needs both sides to come from the same
 * Scorpio serialization; see {@link ReplicationFixtures#categoryEntityJson}.
 */
public final class NodeANotarizer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private NodeANotarizer() {
    }

    public static AuditRecord seedAndNotarizeCategoryEntity(NotificationController notificationController,
                                                              AuditRecordRepository auditRecordRepository,
                                                              String entityId) throws JsonProcessingException {
        ScorpioInflator.addEntitiesToBroker(
                ContainerManager.getBaseUriForScorpioA(), "[" + ReplicationFixtures.categoryEntityJson(entityId) + "]");
        String scorpioCanonicalJson = ScorpioProbe.getEntity(ContainerManager.getBaseUriForScorpioA(), entityId);

        Map<String, Object> dataMap = OBJECT_MAPPER.readValue(scorpioCanonicalJson, Map.class);
        BrokerNotification notification = ReplicationFixtures.brokerNotificationFrom(dataMap);

        // Reuses the publish flow's retry-with-a-fresh-attempt strategy, except the entity id and
        // notification stay fixed across attempts: this only needs the trail's latest row to be
        // PUBLISHED/PRODUCER, so any stray duplicate RECEIVED rows left behind by a lost
        // (pre-readiness) attempt are harmless.
        List<AuditRecord> trail = await().atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofSeconds(3))
                .until(() -> attemptPublish(notificationController, auditRecordRepository, entityId, notification),
                        ReplicationAssertions::lastIsPublishedProducer);

        return trail.get(trail.size() - 1);
    }

    private static List<AuditRecord> attemptPublish(NotificationController notificationController,
                                                      AuditRecordRepository auditRecordRepository,
                                                      String entityId, BrokerNotification notification) {
        notificationController.postBrokerNotification(notification).block(Duration.ofSeconds(10));
        return QueueReadiness.pollWithPartialFallback(
                Duration.ofSeconds(8), Duration.ofMillis(500),
                () -> QueueReadiness.trailFor(auditRecordRepository, entityId),
                ReplicationAssertions::lastIsPublishedProducer);
    }
}
