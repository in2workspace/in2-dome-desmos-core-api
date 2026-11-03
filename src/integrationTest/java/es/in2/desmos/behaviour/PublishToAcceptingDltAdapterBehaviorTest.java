package es.in2.desmos.behaviour;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.repositories.AuditRecordRepository;
import es.in2.desmos.infrastructure.controllers.NotificationController;
import es.in2.desmos.support.ContainerManager;
import es.in2.desmos.support.QueueReadiness;
import es.in2.desmos.support.ReplicationAssertions;
import es.in2.desmos.support.ReplicationFixtures;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static es.in2.desmos.domain.utils.ApplicationConstants.HASH_PREFIX;
import static es.in2.desmos.domain.utils.ApplicationUtils.calculateSHA256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The producer side of replication when the DLT adapter accepts the publication. The real DLT adapter of the
 * integration environment has no blockchain behind it and rejects every publication, so this node A is pointed at
 * a stub adapter (MockWebServer) that answers the publication with a transaction hash. A broker notification for a
 * locally-owned entity must then be audited RECEIVED, CREATED and PUBLISHED, all as trader PRODUCER, and the stub
 * must have received the BlockchainTxPayload for that entity.
 * <p>
 * Uses its own context (a different DLT adapter URL) with the default MOCK web environment and no external access
 * nodes, so it does not compete with the replication tests for node A's fixed port.
 */
@SpringBootTest
@Testcontainers
class PublishToAcceptingDltAdapterBehaviorTest {

    private static final String TX_HASH = "0x" + "ab".repeat(32);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final MockWebServer DLT_ADAPTER_STUB = new MockWebServer();
    // Publication payloads received by the stub, by the entity id taken from their dataLocation
    private static final Map<String, JsonNode> PUBLISHED_PAYLOADS = new ConcurrentHashMap<>();

    static {
        DLT_ADAPTER_STUB.setDispatcher(new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith("/api/v1/publishEvent")) {
                    recordPublication(request.getBody().readUtf8());
                    return json("{\"transactionHash\":\"" + TX_HASH + "\"}");
                }
                // Subscriptions (listing returns none, creating one is accepted) and any other call
                return json("[]");
            }
        });
        try {
            DLT_ADAPTER_STUB.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private NotificationController notificationController;

    @Autowired
    private AuditRecordRepository auditRecordRepository;

    @DynamicPropertySource
    static void setDynamicProperties(DynamicPropertyRegistry registry) {
        ContainerManager.postgresqlProperties(registry);
        // Replaces the real (always rejecting) DLT adapter of node A with the stub
        registry.add("dlt-adapter.externalDomain", () -> DLT_ADAPTER_STUB.url("/").toString());
    }

    @AfterEach
    void cleanUp() {
        auditRecordRepository.deleteAll().block();
    }

    @AfterAll
    static void stopStub() throws IOException {
        DLT_ADAPTER_STUB.shutdown();
    }

    @Test
    void publishingALocallyOwnedEntityToAnAcceptingDltAdapterRecordsItAsPublished() throws Exception {
        // As in PublishFlowBehaviorTest: the publish queue consumer is only attached once the startup chain has
        // finished, and an event posted before that is lost, so a fresh entity is posted until one progresses.
        List<AuditRecord> trail = await()
                .atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofSeconds(3))
                .until(this::postFreshNotificationAndCheckProgress, ReplicationAssertions::lastIsPublishedProducer);

        ReplicationAssertions.assertProducerTrail(trail);

        AuditRecord created = trail.get(1);
        AuditRecord published = trail.get(2);
        String entityId = published.getEntityId();
        assertThat(published.getEntityHashLink()).isEqualTo(created.getEntityHashLink());
        assertThat(published.getDataLocation()).isEqualTo(created.getDataLocation());

        JsonNode payload = PUBLISHED_PAYLOADS.get(entityId);
        assertThat(payload).as("publication received by the DLT adapter stub for %s", entityId).isNotNull();
        assertThat(payload.get("eventType").asText()).isEqualTo("category");
        assertThat(payload.get("entityId").asText()).isEqualTo(HASH_PREFIX + calculateSHA256(entityId));
        assertThat(payload.get("dataLocation").asText()).isEqualTo(published.getDataLocation());
    }

    private List<AuditRecord> postFreshNotificationAndCheckProgress() {
        String entityId = "urn:ngsi-ld:category:" + UUID.randomUUID();
        notificationController.postBrokerNotification(ReplicationFixtures.brokerNotificationForCategory(entityId))
                .block(Duration.ofSeconds(10));
        return QueueReadiness.pollWithPartialFallback(
                Duration.ofSeconds(8), Duration.ofMillis(500),
                () -> QueueReadiness.trailFor(auditRecordRepository, entityId),
                ReplicationAssertions::lastIsPublishedProducer);
    }

    private static void recordPublication(String body) {
        try {
            JsonNode payload = OBJECT_MAPPER.readTree(body);
            String dataLocation = payload.get("dataLocation").asText();
            String withoutHashLink = dataLocation.contains("?") ? dataLocation.substring(0, dataLocation.indexOf('?')) : dataLocation;
            PUBLISHED_PAYLOADS.put(withoutHashLink.substring(withoutHashLink.lastIndexOf('/') + 1), payload);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static MockResponse json(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
