package es.in2.desmos.domain.services.broker.adapter.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.infrastructure.configs.BrokerConfig;
import es.in2.desmos.support.ContainerManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScorpioAdapterEntityIT {

    String requestBody = """
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

    @Autowired
    private ScorpioAdapter scorpioAdapter;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BrokerConfig brokerConfig;

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
    }

    @Test
    @Order(1)
    void postEntityTest() {
        webClient.post()
                .uri(brokerConfig.getEntitiesPath())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Mono.just(requestBody), String.class)
                .exchangeToMono(clientResponse -> {
                    HttpStatusCode status = clientResponse.statusCode();
                    System.out.println("HTTP Status Code: " + status);
                    return clientResponse.bodyToMono(Void.class);
                });

        Mono<Void> result = scorpioAdapter.postEntity("processId", requestBody);

        StepVerifier.create(result)
                .verifyComplete();
    }

    @Test
    @Order(2)
    void getEntityByIdTest() {
        String entityId = "urn:ngsi-ld:ProductOffering:122355255";

        webClient.get()
                .uri(brokerConfig.getEntitiesPath() + "/" + entityId)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .retry(3);

        Mono<String> result = scorpioAdapter.getEntityById("processId", entityId);

        StepVerifier.create(result)
                .expectNextMatches(entity -> {
                    return entity.contains(entityId);
                })
                .verifyComplete();
    }

    @Test
    @Order(3)
    void getEntityByIdNotFoundTest() {
        String entityId = "urn:ngsi-ld:ProductOffering:DoesNotExist";

        WebClient mockWebClient = mock(WebClient.class);
        WebClient.RequestHeadersUriSpec mockRequestHeadersUriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec mockRequestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec mockResponseSpec = mock(WebClient.ResponseSpec.class);

        when(mockWebClient.get()).thenReturn(mockRequestHeadersUriSpec);
        when(mockRequestHeadersUriSpec.uri(anyString())).thenReturn(mockRequestHeadersSpec);
        when(mockRequestHeadersSpec.accept(any())).thenReturn(mockRequestHeadersSpec);
        when(mockRequestHeadersSpec.retrieve()).thenReturn(mockResponseSpec);
        when(mockResponseSpec.bodyToMono(String.class)).thenReturn(Mono.error(new WebClientResponseException("Not Found", 404, "Not Found", HttpHeaders.EMPTY, null, null)));

        Mono<String> result = scorpioAdapter.getEntityById("processId", entityId);

        StepVerifier.create(result)
                .verifyComplete();
    }

    @Test
    @Order(4)
    void updateEntityTestWithJsonLd() {
        String requestBodyWithContext = """
                {
                    "@context": "https://schema.org",
                    "id": "urn:ngsi-ld:ProductOffering:122355255",
                    "type": "ProductOffering",
                    "name": {
                        "type": "Property",
                        "value": "ProductOffering 1 updated"
                    },
                    "description": {
                        "type": "Property",
                        "value": "ProductOffering 1 description updated"
                    }
                }""";

        webClient.patch()
                .uri(brokerConfig.getEntitiesPath() + "/urn:ngsi-ld:ProductOffering:122355255/attrs")
                .contentType(MediaType.valueOf("application/ld+json"))
                .bodyValue(requestBodyWithContext)
                .retrieve()
                .bodyToMono(Void.class)
                .retry(3);

        Mono<Void> result = scorpioAdapter.updateEntity("processId", requestBodyWithContext);

        StepVerifier.create(result)
                .verifyComplete();
    }

    @Test
    @Order(5)
    void updateEntityTestWithJson() {
        webClient.patch()
                .uri(brokerConfig.getEntitiesPath() + "/urn:ngsi-ld:ProductOffering:122355255/attrs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Void.class)
                .retry(3);

        Mono<Void> result = scorpioAdapter.updateEntity("processId", requestBody);

        StepVerifier.create(result)
                .verifyComplete();
    }

    @Test
    @Order(6)
    void deleteEntityByIdTest() {
        String entityId = "urn:ngsi-ld:ProductOffering:122355255";

        webClient.delete()
                .uri(brokerConfig.getEntitiesPath() + "/" + entityId)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(Void.class)
                .retry(3);

        Mono<Void> result = scorpioAdapter.deleteEntityById("processId", entityId);

        StepVerifier.create(result)
                .verifyComplete();
    }
}