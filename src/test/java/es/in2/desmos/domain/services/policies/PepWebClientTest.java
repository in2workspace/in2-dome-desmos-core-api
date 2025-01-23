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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

        HttpHeaders headers = new HttpHeaders();

        headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer <token>");
        headers.add("Custom-Header", "CustomValue");

        when(pepConfig.getExternalDomain()).thenReturn(mockWebServer.url("/").toString());

        mockWebServer.enqueue(new MockResponse()
                .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .addHeader(HttpHeaders.AUTHORIZATION, "Bearer <token>")
                .addHeader("Custom-Header", "CustomValue"));

        String path = "/api/v1/sync/p2p/entities/urn:catalog:1";
        Mono<Void> result = pepWebClient.doRequest(headers, path);

        StepVerifier.create(result)
                .verifyComplete();

        var recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getPath()).isEqualTo(path);
        assertThat(recordedRequest.getHeader(HttpHeaders.CONTENT_TYPE)).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        assertThat(recordedRequest.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer <token>");
        assertThat(recordedRequest.getHeader("Custom-Header")).isEqualTo("CustomValue");
    }
}