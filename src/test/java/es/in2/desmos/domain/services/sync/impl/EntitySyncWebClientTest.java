package es.in2.desmos.domain.services.sync.impl;

import es.in2.desmos.domain.models.Id;
import es.in2.desmos.infrastructure.security.M2MAccessTokenProvider;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntitySyncWebClientTest {

    @Mock
    private M2MAccessTokenProvider mockTokenProvider;

    @InjectMocks
    private EntitySyncWebClientImpl entitySyncWebClient;

    private MockWebServer mockWebServer;


    @BeforeEach
    void setUp() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        WebClient webClient = WebClient.builder().baseUrl(mockWebServer.url("/").toString()).build();
        entitySyncWebClient = new EntitySyncWebClientImpl(webClient, mockTokenProvider);
    }

    @AfterEach
    void tearDown() throws Exception {
        mockWebServer.shutdown();
    }

    @Test
    void makeRequest_shouldReturnFluxOfEntityValues() throws Exception {
        String mockAccessToken = "mock-access-token";
        when(mockTokenProvider.getM2MAccessToken()).thenReturn(Mono.just(mockAccessToken));

        String issuer = mockWebServer.url("/").toString();
        Mono<String> issuerMono = Mono.just(issuer);

        Id[] ids = {new Id("1"), new Id("2")};
        Mono<Id[]> entitySyncRequest = Mono.just(ids);

        String entitiesJson = """
                [
                  {
                    "value": "value1"
                  },
                  {
                    "value": "value2"
                  }
                ]
                """;

        mockWebServer.enqueue(new MockResponse()
                .setBody(entitiesJson)
                .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setResponseCode(200));

        Flux<String> result = entitySyncWebClient.makeRequest("process1", issuerMono, entitySyncRequest);

        StepVerifier.create(result)
                .expectNext("value1")
                .expectNext("value2")
                .verifyComplete();

        var recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getPath()).isEqualTo("/api/v1/sync/p2p/entities");
        assertThat(recordedRequest.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + mockAccessToken);
        assertThat(recordedRequest.getHeader(HttpHeaders.CONTENT_TYPE)).isEqualTo("application/json");
    }
}