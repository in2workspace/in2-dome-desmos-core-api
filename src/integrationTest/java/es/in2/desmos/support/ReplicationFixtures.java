package es.in2.desmos.support;

import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.models.BlockchainNotification;
import es.in2.desmos.domain.models.BrokerNotification;
import es.in2.desmos.domain.utils.ApplicationUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Payloads shared across replication behaviour tests: notification envelopes and entity JSON. */
public final class ReplicationFixtures {

    private ReplicationFixtures() {
    }

    /** Wraps an arbitrary NGSI-LD entity map (e.g. Scorpio's own GET response) in a notification envelope. */
    public static BrokerNotification brokerNotificationFrom(Map<String, Object> dataMap) {
        return BrokerNotification.builder()
                .id("urn:ngsi-ld:notification:" + UUID.randomUUID())
                .type("Notification")
                .subscriptionId("urn:ngsi-ld:Subscription:" + UUID.randomUUID())
                .notifiedAt("2024-09-05T12:00:00Z")
                .data(List.of(dataMap))
                .build();
    }

    /**
     * A hand-written "category" notification, for publish-path tests that don't need to round-trip
     * through Scorpio first. NOT suitable for subscribe-path tests: node B's hash/hashlink
     * verification requires the notification's {@code data[0]} to be Scorpio's own serialization
     * (see {@link #categoryEntityJson}), not a literal that merely looks equivalent.
     */
    public static BrokerNotification brokerNotificationForCategory(String entityId) {
        String json = """
                {
                    "id": "urn:ngsi-ld:notification:%s",
                    "type": "Notification",
                    "subscriptionId": "urn:ngsi-ld:Subscription:%s",
                    "notifiedAt": "2024-09-05T12:00:00Z",
                    "data": [
                        {
                            "id": "%s",
                            "type": "category",
                            "lastUpdate": "2024-09-05T12:00:00Z",
                            "lifecycleStatus": {
                                "type": "Property",
                                "value": "Launched"
                            },
                            "version": "1.0"
                        }
                    ]
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), entityId);
        return readNotification(json);
    }

    /** NGSI-LD JSON for a seedable "category" entity, used by subscribe-path tests. */
    public static String categoryEntityJson(String entityId) {
        return """
                {
                    "id": "%s",
                    "type": "category",
                    "version": {"type": "Property", "value": "1.0"},
                    "lifecycleStatus": {"type": "Property", "value": "Launched"},
                    "validFor": {"type": "Property", "value": {
                        "startDateTime": "2024-01-01T00:00:00.000Z",
                        "endDateTime": "2099-01-01T00:00:00.000Z"
                    }},
                    "lastUpdate": {"type": "Property", "value": "2024-09-05T12:00:00Z"}
                }
                """.formatted(entityId);
    }

    /**
     * A publish-path notification for a non-free type ("category") with a lifecycleStatus outside
     * {@code {Launched, Retired, Obsolete}} -- R10, GP_1 should reject this.
     */
    public static BrokerNotification rejectedByLifecycleStatusNotification(String entityId) {
        return brokerNotificationFromEntityJson("""
                {
                    "id": "%s",
                    "type": "category",
                    "lifecycleStatus": {"type": "Property", "value": "Draft"},
                    "validFor": {"type": "Property", "value": {
                        "startDateTime": "2024-01-01T00:00:00.000Z",
                        "endDateTime": "2099-01-01T00:00:00.000Z"
                    }},
                    "lastUpdate": {"type": "Property", "value": "2024-09-05T12:00:00Z"}
                }
                """.formatted(entityId));
    }

    /**
     * A publish-path notification with a valid lifecycleStatus but a {@code validFor.endDateTime}
     * already in the past -- R10, GP_2 should reject this.
     */
    public static BrokerNotification expiredValidForNotification(String entityId) {
        String endDateTime = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        return brokerNotificationFromEntityJson("""
                {
                    "id": "%s",
                    "type": "category",
                    "lifecycleStatus": {"type": "Property", "value": "Launched"},
                    "validFor": {"type": "Property", "value": {
                        "startDateTime": "2024-01-01T00:00:00.000Z",
                        "endDateTime": "%s"
                    }},
                    "lastUpdate": {"type": "Property", "value": "2024-09-05T12:00:00Z"}
                }
                """.formatted(entityId, endDateTime));
    }

    /**
     * A publish-path notification with a valid lifecycleStatus but a {@code validFor.startDateTime}
     * not yet reached -- R10, GP_2 should reject this.
     */
    public static BrokerNotification futureValidForNotification(String entityId) {
        String startDateTime = Instant.now().plus(1, ChronoUnit.DAYS).toString();
        return brokerNotificationFromEntityJson("""
                {
                    "id": "%s",
                    "type": "category",
                    "lifecycleStatus": {"type": "Property", "value": "Launched"},
                    "validFor": {"type": "Property", "value": {
                        "startDateTime": "%s",
                        "endDateTime": "2099-01-01T00:00:00.000Z"
                    }},
                    "lastUpdate": {"type": "Property", "value": "2024-09-05T12:00:00Z"}
                }
                """.formatted(entityId, startDateTime));
    }

    /**
     * A publish-path notification for a {@code LIFECYCLE_STATUS_FREE_TYPES} type ("quote") with no
     * lifecycleStatus at all -- R10, GP_1's free-type exemption should accept this.
     */
    public static BrokerNotification freeTypeWithNoLifecycleStatusNotification(String entityId) {
        return brokerNotificationFromEntityJson("""
                {
                    "id": "%s",
                    "type": "quote",
                    "lastUpdate": {"type": "Property", "value": "2024-09-05T12:00:00Z"}
                }
                """.formatted(entityId));
    }

    /** Wraps a raw NGSI-LD entity JSON literal (e.g. an {@code EntityMother} fixture) in a notification envelope. */
    @SuppressWarnings("unchecked")
    public static BrokerNotification brokerNotificationFromEntityJson(String entityJson) {
        try {
            Map<String, Object> dataMap = new com.fasterxml.jackson.databind.ObjectMapper().readValue(entityJson, Map.class);
            return brokerNotificationFrom(dataMap);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An arbitrary, well-formed DLT address that is deliberately absent from either node's trusted list (R12). */
    public static final String UNTRUSTED_DLT_SENDER_ADDRESS = "0x1111111111111111111111111111111111111111";

    /** A synthetic {@link BlockchainNotification} standing in for a real DLT delivery, built from an already-notarized {@link AuditRecord}. */
    public static BlockchainNotification dltNotificationFrom(AuditRecord published, String entityId) {
        return dltNotification(ContainerManager.getTrustedDltSenderAddress(), published.getEntityType(),
                published.getDataLocation(), entityId, "0x" + published.getEntityHash());
    }

    /**
     * As {@link #dltNotificationFrom(AuditRecord, String)}, but with the hashlink in
     * {@code dataLocation} corrupted -- for R11, the integrity negative scenario.
     * {@code SubscribeWorkflowImpl} swallows the resulting {@code HashLinkException} internally
     * (the POST still returns 2xx), so this is only useful for a test that asserts on state
     * (the entity never lands, the trail never leaves RECEIVED), not on the HTTP response.
     */
    public static BlockchainNotification dltNotificationWithTamperedHashlink(AuditRecord published, String entityId) {
        return dltNotification(ContainerManager.getTrustedDltSenderAddress(), published.getEntityType(),
                tamperHashlink(published.getDataLocation()), entityId, "0x" + published.getEntityHash());
    }

    /**
     * A synthetic DLT notification from {@link #UNTRUSTED_DLT_SENDER_ADDRESS} -- for R12, the
     * trust-gate negative scenario. No real notarization is needed: node B's trust check runs
     * before any of the entity-specific fields are ever read, so this is safe to build without
     * an {@link AuditRecord}.
     */
    public static BlockchainNotification untrustedDltNotification(String entityId) {
        String dataLocation = ContainerManager.getBaseUriForScorpioA()
                + "/ngsi-ld/v1/entities/" + entityId + "?hl=" + "0".repeat(64);
        return dltNotification(UNTRUSTED_DLT_SENDER_ADDRESS, "category", dataLocation, entityId, "0x" + sha256(entityId));
    }

    private static BlockchainNotification dltNotification(String senderAddress, String eventType, String dataLocation,
                                                            String entityId, String previousEntityHashLink) {
        return BlockchainNotification.builder()
                .id(1L)
                .publisherAddress(senderAddress)
                .ethereumAddress(senderAddress)
                .eventType(eventType)
                .timestamp(System.currentTimeMillis() / 1000)
                .dataLocation(dataLocation)
                .relevantMetadata(List.of("local"))
                .entityId("0x" + sha256(entityId))
                .previousEntityHashLink(previousEntityHashLink)
                .build();
    }

    private static String tamperHashlink(String dataLocation) {
        int idx = dataLocation.indexOf("?hl=");
        if (idx < 0) {
            throw new IllegalArgumentException("dataLocation has no ?hl= hashlink to tamper: " + dataLocation);
        }
        return dataLocation.substring(0, idx) + "?hl=" + "0".repeat(64);
    }

    private static String sha256(String value) {
        try {
            return ApplicationUtils.calculateSHA256(value);
        } catch (java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BrokerNotification readNotification(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, BrokerNotification.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
