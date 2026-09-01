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

import java.util.List;

@SpringBootTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PublishWorkflowBehaviorTest {

    private final Logger log = LoggerFactory.getLogger(PublishWorkflowBehaviorTest.class);

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .build();

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
    public void cleanUp() {
        auditRecordRepository.deleteAll().block();
    }

    @Order(1)
    @Test
    void publishWorkflowBehaviorTest() {
        log.info("Starting Publish Workflow Behavior Test...");
        /*
            Given a BrokerNotification, we will send a POST request emulating the broker behavior.
            When the POST request is received, the application will create a BlockchainTxPayload,
            and publish it into the blockchain.
            During the process, three AuditRecord will be created with the information of the transaction:
            RECEIVED, CREATED, and PUBLISHED.
         */

        // Given
        String brokerNotificationJSON = """
                {
                    "id": "urn:ngsi-ld:notification:e6aca76b-d5d1-4e7b-8bf9-228e1dd701d5",
                    "type": "Notification",
                    "data": [
                        {
                            "id": "urn:ngsi-ld:ProductOffering:3645a0de-d74f-42c5-86ab-e27ccbdf0a9c",
                            "type": "ProductOffering",
                            "href": "http://localhost:9090/ngsi-ld/v1/entities/urn:ngsi-ld:ProductOffering:3645a0de-d74f-42c5-86ab-e27ccbdf0a9c",
                            "description": "Example of a Product offering for cloud services suite",
                            "lastUpdate": "2024-04-01T12:00:00Z",
                            "lifecycleStatus": {
                              "type": "Property",
                              "value": "Launched"
                            },
                            "name": "Cloud Services Suite",
                            "statusReason": "The product is currently active and available",
                            "version": "v1.2",
                            "agreement": [
                                {
                                    "id": "agreement-001",
                                    "href": "http://localhost:9090/ngsi-ld/v1/entities/urn:ngsi-ld:Agreement:c668b804-d27c-46f2-84c6-bdec7010316b"
                                }
                            ],
                            "attachment": [
                                {
                                    "id": "urn:ngsi-ld:Attachment:d194e464-76ad-4799-a0f6-78cd64ef4567",
                                    "href": "http://localhost:9090/ngsi-ld/v1/entities/urn:ngsi-ld:Attachment:d194e464-76ad-4799-a0f6-78cd64ef4567",
                                    "attachmentType": "Documentation",
                                    "url": "http://example.com/docs/cloud-service-suite.pdf"
                                }
                            ],
                            "bundledProductOffering": [],
                            "category": [
                                {
                                    "id": "urn:ngsi-ld:Category:d7b41fec-9903-4003-8f67-500b0d512a0c",
                                    "name": "Cloud Services"
                                }
                            ],
                            "channel": [
                                {
                                    "id": "urn:ngsi-ld:Channel:f0e72fa9-037e-47a2-b791-d4d962a48904",
                                    "name": "Online Sales"
                                }
                            ],
                            "marketSegment": [
                                {
                                    "id": "urn:ngsi-ld:MarketSegment:50080d11-72f1-4d1f-9946-e6e20b41cbbf",
                                    "name": "Enterprise"
                                }
                            ],
                            "place": [
                                {
                                    "id": "urn:ngsi-ld:Place:402518b6-dc5a-420a-8a0f-d346f83c71e1",
                                    "name": "Global"
                                }
                            ],
                            "prodSpecCharValueUse": [],
                            "productOfferingPrice": [
                                {
                                    "id": "urn:ngsi-ld:ProductOfferingPrice:6c760824-5479-45fb-8a3a-5255b4efc435",
                                    "name": "Standard Package",
                                    "price": {
                                        "amount": 99.99,
                                        "currency": "USD"
                                    }
                                }
                            ],
                            "productOfferingRelationship": [],
                            "productOfferingTerm": [
                                {
                                    "name": "Subscription Term",
                                    "duration": "12 months"
                                }
                            ],
                            "productSpecification": {
                                "id": "urn:ngsi-ld:ProductSpecification:1f0f54f6-87b3-4f6e-9435-89e073b5fd42",
                                "name": "Cloud Service Specification"
                            },
                            "resourceCandidate": {
                                "id": "urn:ngsi-ld:ResourceCandidate:426a50cb-6cbf-4a34-bb5f-40a9b06186a9",
                                "name": "Compute Resources"
                            },
                            "serviceCandidate": {
                                "id": "urn:ngsi-ld:ServiceCandidate:6e8d5e3d-88d8-41b7-a3bd-606df7e811e3",
                                "name": "SaaS"
                            },
                            "serviceLevelAgreement": {
                                "id": "urn:ngsi-ld:ServiceLevelAgreement:b3e22a77-f846-4f19-b8c2-505df2360e58",
                                "name": "99.9% Uptime Guarantee"
                            },
                            "validFor": {
                                "type": "Property",
                                    "value": {
                                        "startDateTime": "2024-01-01T00:00:00Z",
                                        "endDateTime": "9999-12-31T23:59:59Z"
                                    }
                            }
                        }
                    ],
                    "notifiedAt": "2024-04-01T13:53:57.640000Z",
                    "subscriptionId": "urn:ngsi-ld:subscription:43109437-bbee-4187-9892-e325210d7ca4"
                }
                """;

        // When
        try {
            log.info("1. Create a BrokerNotification and send a POST request to the application");
            BrokerNotification brokerNotification = objectMapper.readValue(brokerNotificationJSON, BrokerNotification.class);

            notificationController.postBrokerNotification(brokerNotification).block();
            // Not subscribing to pendingPublishEventsQueue.getEventStream() here: the queue sink is
            // unicast (single consumer), and ApplicationRunner's PublishWorkflow subscription is the
            // one real consumer - a second subscribe() here would throw instead of just observing.
            // Then
            log.info("2. Check values in the AuditRecord table:");
            List<AuditRecord> auditRecordList = auditRecordRepository.findAll().collectList().block();
            log.info("Result: {}", auditRecordList);
        } catch (Exception e) {
            log.error("Error: {}", e.getMessage());
        }
        Assertions.assertNotNull(auditRecordRepository
                .findByEntityId("urn:ngsi-ld:ProductOffering:3645a0de-d74f-42c5-86ab-e27ccbdf0a9c").blockFirst());
    }

}
