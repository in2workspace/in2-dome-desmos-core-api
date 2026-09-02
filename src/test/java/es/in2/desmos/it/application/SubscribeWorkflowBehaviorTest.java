package es.in2.desmos.it.application;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.models.BlockchainNotification;
import es.in2.desmos.domain.repositories.AuditRecordRepository;
import es.in2.desmos.infrastructure.controllers.NotificationController;
import es.in2.desmos.it.ContainerManager;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.List;

import static org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_CLASS;

@SpringBootTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = BEFORE_CLASS)
@TestExecutionListeners({DirtiesContextTestExecutionListener.class})
class SubscribeWorkflowBehaviorTest {

    private final Logger log = LoggerFactory.getLogger(SubscribeWorkflowBehaviorTest.class);

    private final ObjectMapper objectMapper =
            JsonMapper.builder()
                    .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                    .build();

    String request = """
            {
                "id": "urn:ngsi-ld:ProductOffering:122355255",
                "type": "ProductOffering",
                "name": {
                    "type": "Property",
                    "value": "ProductOffering 1"
                },
                "description": {
                    "type": "Property",
                    "value": "ProductOffering 1 description"
                }
            }""";

    String updateRequest = """
            {
                "id": "urn:ngsi-ld:ProductOffering:122355255",
                "type": "ProductOffering",
                "name": {
                    "type": "Property",
                    "value": "ProductOffering 2"
                },
                "description": {
                    "type": "Property",
                    "value": "ProductOffering 2 description"
                }
            }""";

    @Autowired
    private NotificationController notificationController;

    @Autowired
    private AuditRecordRepository auditRecordRepository;

    private static MockWebServer mockWebServer;

    @BeforeAll
    static void beforeAll() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        mockWebServer.setDispatcher(trustedAccessNodesListServerDispatcher());
    }

    @AfterAll
    static void afterAll() throws IOException {
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    private static Dispatcher trustedAccessNodesListServerDispatcher() {
        return new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest recordedRequest) {
                return new MockResponse()
                        .setBody("""
                                organizations:
                                  - name: DOME
                                    publicKey: 0x0486573f96a9e5a0007855cba27af53d2d73d69cc143266bc336e361d2f5124f6639c813e62a1c8642132de455b72d65c620f18d69c09e30123d420fcb85de361d
                                    url: https://desmos.dome-marketplace-sbx.org
                                    dlt_address: 0x40b0ab9dfd960064fb7e9fdf77f889c71569e349055ff563e8d699d8fa97fa90""")
                        .addHeader("Content-Type", "application/json");
            }
        };
    }

    @DynamicPropertySource
    static void setDynamicProperties(DynamicPropertyRegistry registry) {
        ContainerManager.postgresqlProperties(registry);
        registry.add("access-node.trustedAccessNodesList", () -> mockWebServer.url("").toString());
    }

    private static String getString() {
        String url = ContainerManager.getBaseUriForScorpioA();
        return String.format("""
                {
                    "id": 2240,
                    "publisherAddress": "0x40b0ab9dfd960064fb7e9fdf77f889c71569e349055ff563e8d699d8fa97fa90",
                    "eventType": "ProductOffering",
                    "timestamp": 1712753824,
                    "dataLocation": "%s/ngsi-ld/v1/entities/urn:ngsi-ld:ProductOffering:122355255?hl=abbc168236d38354add74d65698f37941947127290cd40a90b4dbe7eb68d25c0",
                    "relevantMetadata": [],
                    "entityId": "0x4eb401aa1248b6a95c298d0747eb470b6ba6fc3f54ea630dc6c77f23ad1abe3e",
                    "previousEntityHash": "0xabbc168236d38354add74d65698f37941947127290cd40a90b4dbe7eb68d25c0",
                    "ethereumAddress": "0x40b0ab9dfd960064fb7e9fdf77f889c71569e349055ff563e8d699d8fa97fa90"
                }""", url);
    }

    private static String getBlockchainNotificationJson() {
        String url = ContainerManager.getBaseUriForScorpioA();
        return String.format("""
                {
                    "id": 2240,
                    "publisherAddress": "0x40b0ab9dfd960064fb7e9fdf77f889c71569e349055ff563e8d699d8fa97fa90",
                    "eventType": "ProductOffering",
                    "timestamp": 1712753824,
                    "dataLocation": "%s/ngsi-ld/v1/entities/urn:ngsi-ld:ProductOffering:122355255?hl=64f516030ab35b7f18d8d06054079e85070ea04f1c0dab5291be8184a28025fa",
                    "relevantMetadata": [],
                    "entityId": "0x4eb401aa1248b6a95c298d0747eb470b6ba6fc3f54ea630dc6c77f23ad1abe3e",
                    "previousEntityHash": "0xabbc168236d38354add74d65698f37941947127290cd40a90b4dbe7eb68d25c0",
                    "ethereumAddress": "0x40b0ab9dfd960064fb7e9fdf77f889c71569e349055ff563e8d699d8fa97fa90"
                }""", url);
    }

    /*
        Given a BlockchainNotification, we will send a POST request emulating the broker behavior.
        When the POST request is received, the application will retrieve a BrokerEntity from the external broker,
        and then it will send a POST request to the local broker.
        Finally, the application will create an AuditRecord with the status of the operation.
    */
    @Order(0)
    @Test
    void subscribeWorkflowBehaviorTest() {
        log.info("Starting Subscribe Workflow Behavior Test...");
        log.info("1. Send a POST request to the external broker in order to retrieve the entity later");

        WebClient.create().post()
                .uri(ContainerManager.getBaseUriForScorpioA() + "/ngsi-ld/v1/entities")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.isSameCodeAs(HttpStatusCode.valueOf(409)), response -> Mono.empty())
                .bodyToMono(String.class).block();

        String blockchainNotificationJson = getString();
        // When
        try {
            log.info("1. Create a BlockchainNotification and send a POST request to the application");
            BlockchainNotification blockchainNotification = objectMapper.readValue(blockchainNotificationJson, BlockchainNotification.class);
            StepVerifier.create(notificationController.postDLTNotification(blockchainNotification))
                    .verifyComplete();
            // Not subscribing to pendingSubscribeEventsQueue.getEventStream() here: the queue sink is
            // unicast (single consumer), and ApplicationRunner's SubscribeWorkflow subscription is the
            // one real consumer - a second subscribe() here would throw instead of just observing.
            log.info("2. Check values in the AuditRecord table:");
            List<AuditRecord> auditRecordList = auditRecordRepository.findAll().collectList().block();
            log.info("Result: {}", auditRecordList);
        } catch (Exception e) {
            log.error("Error while sending the BlockchainNotification: {}", e.getMessage());
        }
    }

    @Order(1)
    @Test
    void subscribeWorkflowBehaviorTest_UPDATE() {
        log.info("Starting Subscribe Workflow Behavior Test...");
        log.info("2. Send a POST request to the external broker in order to retrieve the entity later");
        WebClient.create().patch()
                .uri(ContainerManager.getBaseUriForScorpioA() + "/ngsi-ld/v1/entities" + "/urn:ngsi-ld:ProductOffering:122355255/attrs")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updateRequest)
                .retrieve()
                .onStatus(status -> status.isSameCodeAs(HttpStatusCode.valueOf(409)), response -> Mono.empty())
                .bodyToMono(String.class).block();

        String blockchainNotificationJson = getBlockchainNotificationJson();
        // When
        try {
            log.info("1. Create a BlockchainNotification and send a POST request to the application");
            BlockchainNotification blockchainNotification = objectMapper.readValue(blockchainNotificationJson, BlockchainNotification.class);
            StepVerifier.create(notificationController.postDLTNotification(blockchainNotification))
                    .verifyComplete();
            // Not subscribing to pendingSubscribeEventsQueue.getEventStream() here: the queue sink is
            // unicast (single consumer), and ApplicationRunner's SubscribeWorkflow subscription is the
            // one real consumer - a second subscribe() here would throw instead of just observing.
            log.info("2. Check values in the AuditRecord table:");
            List<AuditRecord> auditRecordList = auditRecordRepository.findAll().collectList().block();
            log.info("Result: {}", auditRecordList);
        } catch (Exception e) {
            log.error("Error while sending the BlockchainNotification: {}", e.getMessage());
        }
    }

}
