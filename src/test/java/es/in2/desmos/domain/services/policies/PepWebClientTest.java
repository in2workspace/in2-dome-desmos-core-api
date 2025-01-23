package es.in2.desmos.domain.services.policies;

import es.in2.desmos.domain.services.policies.impl.PepWebClientImpl;
import es.in2.desmos.infrastructure.configs.PepConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PepWebClientTest {

    @Mock
    private PepConfig pepConfig;

    @InjectMocks
    private PepWebClientImpl pepWebClient;

    private MockWebServer mockWebServer;


    @BeforeEach
    void setUp() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        WebClient webClient = WebClient.builder().baseUrl(mockWebServer.url("/").toString()).build();
        pepWebClient = new PepWebClientImpl(webClient, pepConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        mockWebServer.shutdown();
    }

    @Test
    void makeRequest_shouldReturnFluxOfEntityValues() throws Exception {
        when(pepConfig.getUrl()).thenReturn(mockWebServer.url("/path").toString());

        mockWebServer.enqueue(new MockResponse());

        String originalUri = "/api/v1/sync/p2p/entities/urn:catalog:1";
        HttpMethod method = HttpMethod.GET;
        String authorization = "Bearer <token>";

        Mono<Void> result = pepWebClient.doRequest(originalUri, method, null, null, authorization);

        StepVerifier.create(result)
                .verifyComplete();

        var recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo(method.name());
        assertThat(recordedRequest.getHeader("X-Original-URI")).isEqualTo(originalUri);
        assertThat(recordedRequest.getHeader("X-Original-Method")).isEqualTo(method.name());
        assertThat(recordedRequest.getHeader("X-Original-Remote-Addr")).isNull();
        assertThat(recordedRequest.getHeader("X-Original-Host")).isNull();
        assertThat(recordedRequest.getHeader("Authorization")).isEqualTo(authorization);
    }
}