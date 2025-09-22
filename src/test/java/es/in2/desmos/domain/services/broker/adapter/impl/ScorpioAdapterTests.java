package es.in2.desmos.domain.services.broker.adapter.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.models.BrokerSubscription;
import es.in2.desmos.domain.utils.EndpointsConstants;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

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
    void getEntitiesByTimeRangeTest() throws IllegalAccessException, NoSuchFieldException {
        //Arrange
        Field field = ScorpioAdapter.class.getDeclaredField("webClient");
        field.setAccessible(true);
        field.set(scorpioAdapter, webClientMock);
        String mockResponse = "[{ \"id\": \"urn:ngsi-ld:ProductOffering:122355255\" }]";
        Flux<String> fluxMockResponse = Flux.just(mockResponse);

        when(responseMock.bodyToFlux(String.class)).thenReturn(fluxMockResponse);
        when(webClientMock.get()).thenReturn(requestHeadersUriMock);
        when(requestHeadersUriMock.uri(anyString())).thenReturn(requestHeadersMock);
        when(requestHeadersMock.accept(any(MediaType.class))).thenReturn(requestHeadersMock);
        when(requestHeadersMock.retrieve()).thenReturn(responseMock);

        //Act
        Flux<String> result = scorpioAdapter.getEntitiesByTimeRange("processId", "timestamp");

        //Assert
        StepVerifier.create(result)
                .expectNextMatches(entity -> {
                    return entity.contains("urn:ngsi-ld:ProductOffering:122355255");
                })
                .verifyComplete();
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
}