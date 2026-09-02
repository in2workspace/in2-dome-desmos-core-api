package es.in2.desmos.it.application;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.models.BrokerNotification;
import es.in2.desmos.domain.repositories.AuditRecordRepository;
import es.in2.desmos.infrastructure.controllers.NotificationController;
import es.in2.desmos.it.ContainerManager;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;

/*
 * Repro test for the "Quote replication" issue reported by BAE/Luca:
 * Quotes created on a source Access Node were not appearing on the target Access Node.
 *
 * This uses the exact Quote payload shared by the client (quote urn:ngsi-ld:quote:9aa2f09d-...,
 * with a nested QuoteItem, attachment and a note with null id/text) to check whether the
 * PublishWorkflow processes a real-world Quote entity without silently failing before
 * blockchain publication - i.e. whether an AuditRecord with status PUBLISHED is ever created
 * for it locally. If this test fails or no PUBLISHED record appears, the bug is on the
 * "publish" side (source Access Node never notarizes the Quote), not on the P2P sync/replication
 * side.
 */
@SpringBootTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QuotePublishWorkflowReproTest {

    private final Logger log = LoggerFactory.getLogger(QuotePublishWorkflowReproTest.class);

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .build();

    private static final String QUOTE_ENTITY_ID = "urn:ngsi-ld:quote:9aa2f09d-c035-42c4-a4b5-52a1caf25d83";

    @Autowired
    private NotificationController notificationController;

    @Autowired
    private AuditRecordRepository auditRecordRepository;

    @DynamicPropertySource
    static void setDynamicProperties(DynamicPropertyRegistry registry) {
        ContainerManager.postgresqlProperties(registry);
    }

    @BeforeEach
    @AfterEach
    void cleanUp() {
        auditRecordRepository.deleteAll().block();
    }

    @Order(1)
    @Test
    void quotePublishWorkflowReproTest() {
        log.info("Starting Quote Publish Workflow Repro Test...");

        // Given: the real Quote payload reported by the client, wrapped as a BrokerNotification
        String brokerNotificationJSON = """
                {
                    "id": "urn:ngsi-ld:notification:quote-repro-0001",
                    "type": "Notification",
                    "data": [
                        {
                            "id": "urn:ngsi-ld:quote:9aa2f09d-c035-42c4-a4b5-52a1caf25d83",
                            "type": "quote",
                            "href": "urn:ngsi-ld:quote:9aa2f09d-c035-42c4-a4b5-52a1caf25d83",
                            "category": "tailored",
                            "description": "Test 26/08",
                            "externalId": "0000",
                            "quoteDate": "2026-08-26T06:56:24.231150881Z",
                            "requestedQuoteCompletionDate": "2026-08-28T23:59:59Z",
                            "quoteItem": [
                                {
                                    "action": "add",
                                    "quantity": 1,
                                    "state": "pending",
                                    "note": [
                                        {
                                            "id": null,
                                            "text": null
                                        }
                                    ],
                                    "productOffering": {
                                        "id": "urn:ngsi-ld:product-offering:984e6f2c-15fd-4e45-ab27-c9f7b6190b98",
                                        "@type": "ProductOfferingRef"
                                    },
                                    "@type": "QuoteItem"
                                }
                            ],
                            "relatedParty": [
                                {
                                    "id": "urn:ngsi-ld:organization:eb6647da-84f2-4645-8d9f-c2905775b561",
                                    "href": "urn:ngsi-ld:organization:eb6647da-84f2-4645-8d9f-c2905775b561",
                                    "name": "urn:ngsi-ld:organization:eb6647da-84f2-4645-8d9f-c2905775b561",
                                    "role": "Seller",
                                    "@referredType": "organization"
                                },
                                {
                                    "id": "urn:ngsi-ld:organization:eb6647da-84f2-4645-8d9f-c2905775b561",
                                    "href": "urn:ngsi-ld:organization:eb6647da-84f2-4645-8d9f-c2905775b561",
                                    "name": "did:elsi:VATIT-12622480155",
                                    "role": "SellerOperator",
                                    "@referredType": "organization"
                                },
                                {
                                    "id": "urn:ngsi-ld:organization:95fdc12e-6889-4f08-8ff8-296b10e8e781",
                                    "href": "urn:ngsi-ld:organization:95fdc12e-6889-4f08-8ff8-296b10e8e781",
                                    "name": "VATIT-05724831002",
                                    "role": "Buyer",
                                    "@type": "RelatedParty",
                                    "@referredType": "organization"
                                },
                                {
                                    "id": "urn:ngsi-ld:organization:df924e5d-e8c8-4ea4-aca8-edaf5acdc109",
                                    "href": "urn:ngsi-ld:organization:df924e5d-e8c8-4ea4-aca8-edaf5acdc109",
                                    "name": "did:elsi:VATSB-12345678J",
                                    "role": "BuyerOperator",
                                    "@referredType": "organization"
                                }
                            ],
                            "state": "inProgress"
                        }
                    ],
                    "notifiedAt": "2026-08-26T06:56:24.231150881Z",
                    "subscriptionId": "urn:ngsi-ld:subscription:quote-repro-0001"
                }
                """;

        // When
        log.info("1. Create a BrokerNotification from the real Quote payload and send it to the application");
        BrokerNotification brokerNotification = Assertions.assertDoesNotThrow(() ->
                        objectMapper.readValue(brokerNotificationJSON, BrokerNotification.class),
                "Failed to parse the Quote BrokerNotification payload");

        Assertions.assertDoesNotThrow(() -> notificationController.postBrokerNotification(brokerNotification).block(),
                "Publishing the Quote entity threw an exception");
        // Note: not subscribing to pendingPublishEventsQueue.getEventStream() here on purpose -
        // the queue sink is now unicast (single consumer), and ApplicationRunner's PublishWorkflow
        // subscription is the one and only real consumer. A second subscribe() here would fight it.

        log.info("2. Check values in the AuditRecord table:");
        List<AuditRecord> auditRecordList = auditRecordRepository.findAll().collectList().block();
        log.info("Result: {}", auditRecordList);

        // Then: the Quote must reach a PUBLISHED AuditRecord, exactly like any other root object type.
        // Poll (via Awaitility, not a fixed sleep) instead of a single check: at app startup, the live
        // PublishWorkflow subscription is only wired up after ApplicationRunner's initial P2P data sync
        // finishes (see ApplicationRunner.java), which can race with this test posting the notification
        // right after context startup.
        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    AuditRecord publishedRecord = auditRecordRepository
                            .findMostRecentPublishedAuditRecordByEntityId(QUOTE_ENTITY_ID)
                            .block();
                    Assertions.assertNotNull(publishedRecord,
                            "No PUBLISHED AuditRecord was created for the Quote yet - PublishWorkflow silently failed to notarize it");
                });
    }

}
