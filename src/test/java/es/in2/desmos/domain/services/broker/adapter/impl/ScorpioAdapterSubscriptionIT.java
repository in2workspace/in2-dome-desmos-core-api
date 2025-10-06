package es.in2.desmos.domain.services.broker.adapter.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.models.BrokerSubscription;
import es.in2.desmos.infrastructure.configs.BrokerConfig;
import es.in2.desmos.infrastructure.configs.EndpointsConfig;
import es.in2.desmos.it.ContainerManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "api.version=v2")
class ScorpioAdapterSubscriptionIT {

    private BrokerSubscription brokerSubscription;

    @Autowired
    private ScorpioAdapter scorpioAdapter;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BrokerConfig brokerConfig;

    @Autowired
    private EndpointsConfig endpointsConfig;

    @Autowired
    private WebClient webClient;

    @LocalServerPort
    private int localServerPort;

    @DynamicPropertySource
    static void setDynamicProperties(DynamicPropertyRegistry registry) {
        ContainerManager.postgresqlProperties(registry);
    }

    @BeforeEach
    void setUp() {
        webClient = WebClient.builder()
                .baseUrl("http://localhost:" + localServerPort)
                .build();
        brokerSubscription = BrokerSubscription.builder()
                .id("urn:subscription:b74a701a-9a3b-4eff-982e-744652fc2abf")
                .type("Subscription")
                .entities(List.of(
                        BrokerSubscription.Entity.builder().type("ProductOffering").build(),
                        BrokerSubscription.Entity.builder().type("Category").build(),
                        BrokerSubscription.Entity.builder().type("Catalogue").build()))
                .notification(BrokerSubscription.SubscriptionNotification.builder()
                        .subscriptionEndpoint(BrokerSubscription.SubscriptionNotification.SubscriptionEndpoint.builder()
                                .uri("http://localhost:8080" + endpointsConfig.brokerNotificationEndpoint())
                                .accept("application/json")
                                .receiverInfo(List.of(
                                        BrokerSubscription.SubscriptionNotification.SubscriptionEndpoint.RetrievalInfoContentType.builder()
                                                .contentType("application/json")
                                                .build()))
                                .build())
                        .build())
                .build();
    }

    @Test
    @Order(1)
    void createOrUpdateSubscriptionTest() {
        webClient.get()
                .uri(brokerConfig.getSubscriptionsPath() + "/" + brokerSubscription.id())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(BrokerSubscription.class)
                .retry(3)
                .flatMap(existingSubscription -> {
                    return webClient.patch()
                            .uri(brokerConfig.getSubscriptionsPath() + "/" + brokerSubscription.id())
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(brokerSubscription)
                            .retrieve()
                            .bodyToMono(Void.class)
                            .retry(3);
                })
                .onErrorResume(WebClientResponseException.NotFound.class, ex -> {
                    return webClient.post()
                            .uri(brokerConfig.getSubscriptionsPath())
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(brokerSubscription)
                            .retrieve()
                            .bodyToMono(Void.class)
                            .retry(3);
                });

        Mono<Void> result = scorpioAdapter.createSubscription("processId", brokerSubscription);

        StepVerifier.create(result)
                .verifyComplete();
    }

    @Test
    @Order(2)
    void updateSubscriptionTest() {
        webClient.patch()
                .uri(brokerConfig.getSubscriptionsPath() + "/" + brokerSubscription.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(brokerSubscription)
                .retrieve()
                .bodyToMono(Void.class)
                .retry(3);

        Mono<Void> result = scorpioAdapter.updateSubscription("processId", brokerSubscription);

        StepVerifier.create(result)
                .verifyComplete();
    }

    @Test
    @Order(3)
    void deleteSubscriptionTest() {
        String processId = "processId";
        String subscriptionId = brokerSubscription.id();

        webClient.delete()
                .uri(brokerConfig.getSubscriptionsPath() + "/" + subscriptionId)
                .retrieve()
                .bodyToMono(Void.class)
                .retry(3);

        Mono<Void> result = scorpioAdapter.deleteSubscription(processId, subscriptionId);

        StepVerifier.create(result)
                .verifyComplete();
    }

}