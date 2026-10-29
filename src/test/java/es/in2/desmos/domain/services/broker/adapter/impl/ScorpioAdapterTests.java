package es.in2.desmos.domain.services.broker.adapter.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import es.in2.desmos.domain.exceptions.BrokerRequestRejectedException;
import es.in2.desmos.domain.models.BrokerEntityWithIdTypeLastUpdateAndVersion;
import es.in2.desmos.domain.models.BrokerSubscription;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.infrastructure.configs.BrokerConfig;
import es.in2.desmos.infrastructure.configs.EndpointsConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScorpioAdapterTests {

    private BrokerSubscription brokerSubscription;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private BrokerConfig brokerConfig;

    @Mock
    private ApiConfig apiConfig;

    @Mock
    private EndpointsConfig endpointsConfig;

    @Mock
    private WebClient webClientMock;

    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriMock;

    @Mock
    private WebClient.RequestHeadersSpec requestHeadersMock;

    @Mock
    private WebClient.ResponseSpec responseMock;

    @Mock
    private WebClient.RequestBodyUriSpec requestBodyUriMock;

    @Mock
    private WebClient.RequestBodySpec PatchRequestBodyMock;

    @Mock
    private WebClient.RequestBodySpec AcceptedRequestBodyMock;

    @BeforeEach
    void setUp() {


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

    @InjectMocks
    private ScorpioAdapter scorpioAdapter;

    @Test
    @SuppressWarnings("unchecked")
    void checkIfSubscriptionExistsShouldFindTheSubscriptionAnywhereInTheList() throws Exception {
        BrokerSubscription otherClientSubscription = BrokerSubscription.builder()
                .id("urn:subscription:other-client")
                .type("Subscription")
                .entities(List.of(BrokerSubscription.Entity.builder().type("ProductOffering").build()))
                .notification(BrokerSubscription.SubscriptionNotification.builder()
                        .subscriptionEndpoint(BrokerSubscription.SubscriptionNotification.SubscriptionEndpoint.builder()
                                .uri("http://other-client:8080/notifications")
                                .build())
                        .build())
                .build();
        Method method = ScorpioAdapter.class.getDeclaredMethod("checkIfSubscriptionExists", BrokerSubscription.class, List.class);
        method.setAccessible(true);

        Optional<BrokerSubscription> found = (Optional<BrokerSubscription>) method.invoke(scorpioAdapter, brokerSubscription,
                List.of(otherClientSubscription, brokerSubscription));
        Optional<BrokerSubscription> notFound = (Optional<BrokerSubscription>) method.invoke(scorpioAdapter, brokerSubscription,
                List.of(otherClientSubscription));

        assertEquals(Optional.of(brokerSubscription), found);
        assertEquals(Optional.empty(), notFound);
    }

    @Test
    void createSubscriptionShouldDeleteDuplicatedSubscriptionsAndIgnoreOthersWithoutEndpoint() throws Exception {
        BrokerSubscription withoutNotification = BrokerSubscription.builder()
                .id("urn:subscription:other-client")
                .type("Subscription")
                .entities(List.of(BrokerSubscription.Entity.builder().type("ProductOffering").build()))
                .build();
        BrokerSubscription duplicate = BrokerSubscription.builder()
                .id("urn:subscription:duplicate")
                .type(brokerSubscription.type())
                .entities(brokerSubscription.entities())
                .notification(brokerSubscription.notification())
                .build();
        String subscriptionsJson = new ObjectMapper().writeValueAsString(List.of(withoutNotification, brokerSubscription, duplicate));
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ngsi-ld/v1/subscriptions", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            if ("GET".equals(exchange.getRequestMethod())) {
                byte[] responseBody = subscriptionsJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, responseBody.length);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(responseBody);
                }
            } else {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            }
        });
        server.start();

        try {
            when(brokerConfig.getSubscriptionsPath()).thenReturn("/ngsi-ld/v1/subscriptions");
            ReflectionTestUtils.setField(scorpioAdapter, "webClient", WebClient.builder()
                    .baseUrl("http://localhost:" + server.getAddress().getPort())
                    .build());

            StepVerifier.create(scorpioAdapter.createSubscription("processId", brokerSubscription))
                    .verifyComplete();

            assertEquals(List.of("GET /ngsi-ld/v1/subscriptions", "DELETE /ngsi-ld/v1/subscriptions/urn:subscription:duplicate"),
                    requests);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testUpdateSubscription() throws Exception {
        // Arrange
        when(PatchRequestBodyMock.accept(any(MediaType.class))).thenReturn(AcceptedRequestBodyMock);
        when(AcceptedRequestBodyMock.contentType(any(MediaType.class))).thenReturn(PatchRequestBodyMock);
        when(webClientMock.patch()).thenReturn(requestBodyUriMock);
        when(requestBodyUriMock.uri(anyString())).thenReturn(PatchRequestBodyMock);
        when(PatchRequestBodyMock.bodyValue(any())).thenReturn(requestHeadersMock);
        when(requestHeadersMock.retrieve()).thenReturn(responseMock);
        when(responseMock.bodyToMono(Void.class)).thenReturn(Mono.empty());

        ReflectionTestUtils.setField(scorpioAdapter, "webClient", webClientMock);

        Method method = ScorpioAdapter.class.getDeclaredMethod("updateSubscription", BrokerSubscription.class);

        method.setAccessible(true);

        Mono<Void> result = (Mono<Void>) method.invoke(scorpioAdapter, brokerSubscription);

        // Act & Assert
        StepVerifier.create(result)
                .verifyComplete();
    }

    @Test
    void postEntityDoesNotRetryOn4xxAndSurfacesBrokerDetail() throws Exception {
        // Arrange
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ngsi-ld/v1/entities", exchange -> {
            requestCount.incrementAndGet();
            byte[] responseBody = "null values are not allowed in NGSI-LD".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, responseBody.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(responseBody);
            }
        });
        server.start();

        try {
            String requestBody = "{\"id\":\"urn:ngsi-ld:ProductOffering:1\",\"type\":\"ProductOffering\"}";
            when(objectMapper.readTree(requestBody)).thenReturn(new ObjectMapper().readTree(requestBody));
            when(brokerConfig.getEntitiesPath()).thenReturn("/ngsi-ld/v1/entities");

            WebClient realWebClient = WebClient.builder()
                    .baseUrl("http://localhost:" + server.getAddress().getPort())
                    .build();
            ReflectionTestUtils.setField(scorpioAdapter, "webClient", realWebClient);

            // Act
            Mono<Void> result = scorpioAdapter.postEntity("processId", requestBody);

            // Assert
            StepVerifier.create(result)
                    .expectErrorSatisfies(throwable -> {
                        assertInstanceOf(BrokerRequestRejectedException.class, throwable);
                        assertTrue(throwable.getMessage().contains("null values are not allowed in NGSI-LD"));
                    })
                    .verify();
            assertEquals(1, requestCount.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void postEntityRejectionCarriesStatusAndProblemDetail() throws Exception {
        // Arrange
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ngsi-ld/v1/entities", exchange -> {
            byte[] responseBody = ("{\"type\":\"https://uri.etsi.org/ngsi-ld/errors/BadRequestData\",\"title\":\"Bad Request Data.\","
                    + "\"detail\":\"Duplicated datasetId or multiple entries with no datasetId found\",\"status\":400}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, responseBody.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(responseBody);
            }
        });
        server.start();

        try {
            String requestBody = "{\"id\":\"urn:ngsi-ld:ProductOffering:1\",\"type\":\"ProductOffering\"}";
            when(objectMapper.readTree(anyString())).thenAnswer(invocation -> new ObjectMapper().readTree((String) invocation.getArgument(0)));
            when(brokerConfig.getEntitiesPath()).thenReturn("/ngsi-ld/v1/entities");

            WebClient realWebClient = WebClient.builder()
                    .baseUrl("http://localhost:" + server.getAddress().getPort())
                    .build();
            ReflectionTestUtils.setField(scorpioAdapter, "webClient", realWebClient);

            // Act
            Mono<Void> result = scorpioAdapter.postEntity("processId", requestBody);

            // Assert
            StepVerifier.create(result)
                    .expectErrorSatisfies(throwable -> {
                        BrokerRequestRejectedException rejection = assertInstanceOf(BrokerRequestRejectedException.class, throwable);
                        assertEquals(400, rejection.getStatus());
                        assertEquals("Duplicated datasetId or multiple entries with no datasetId found", rejection.getDetail());
                        assertTrue(rejection.getMessage().contains("BadRequestData"));
                    })
                    .verify();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void findAllIdTypeAndAttributesByTypePaginatesAndDeduplicatesAcrossPages() throws Exception {
        // Arrange: total=3, pageSize=2. Page 2 (offset=2) re-returns entity "B" from page 1 (offset=0),
        // simulating the kind of overlap that limit/offset without a stable order can produce.
        String entityA = entityJson("A");
        String entityB = entityJson("B");
        String entityC = entityJson("C");
        String firstPageBody = "[" + entityA + "," + entityB + "]";
        String secondPageBody = "[" + entityB + "," + entityC + "]";

        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ngsi-ld/v1/entities/", exchange -> {
            requestCount.incrementAndGet();
            String offset = queryParam(exchange.getRequestURI().getQuery(), "offset");
            byte[] responseBody = ("2".equals(offset) ? secondPageBody : firstPageBody).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("NGSILD-Results-Count", "3");
            exchange.sendResponseHeaders(200, responseBody.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(responseBody);
            }
        });
        server.start();

        try {
            when(brokerConfig.getEntitiesPath()).thenReturn("/ngsi-ld/v1/entities");
            when(brokerConfig.getPageSize()).thenReturn(2);

            WebClient realWebClient = WebClient.builder()
                    .baseUrl("http://localhost:" + server.getAddress().getPort())
                    .build();
            ReflectionTestUtils.setField(scorpioAdapter, "webClient", realWebClient);

            // Act
            Flux<BrokerEntityWithIdTypeLastUpdateAndVersion> result = scorpioAdapter.findAllIdTypeAndAttributesByType(
                    "processId", "ProductOffering", "lastUpdate", "version", "lifecycleStatus", "validFor",
                    BrokerEntityWithIdTypeLastUpdateAndVersion.class);

            // Assert
            StepVerifier.create(result.map(BrokerEntityWithIdTypeLastUpdateAndVersion::getId).collectList())
                    .assertNext(ids -> assertEquals(List.of(
                            "urn:ngsi-ld:ProductOffering:A",
                            "urn:ngsi-ld:ProductOffering:B",
                            "urn:ngsi-ld:ProductOffering:C"), ids))
                    .verifyComplete();
            assertEquals(2, requestCount.get());
        } finally {
            server.stop(0);
        }
    }

    private static String entityJson(String suffix) {
        return "{\"id\":\"urn:ngsi-ld:ProductOffering:" + suffix + "\",\"type\":\"ProductOffering\","
                + "\"version\":\"1.0.0\",\"lastUpdate\":\"2024-01-01T00:00:00Z\",\"lifecycleStatus\":\"Launched\","
                + "\"validFor\":{\"startDateTime\":\"2024-01-01T00:00:00Z\",\"endDateTime\":\"2024-12-31T00:00:00Z\"}}";
    }

    private static String queryParam(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue[0].equals(name)) {
                return keyValue.length > 1 ? keyValue[1] : "";
            }
        }
        return null;
    }
}