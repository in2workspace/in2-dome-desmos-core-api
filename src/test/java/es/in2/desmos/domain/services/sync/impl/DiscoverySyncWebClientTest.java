package es.in2.desmos.domain.services.sync.impl;

import es.in2.desmos.domain.exceptions.DiscoverySyncException;
import es.in2.desmos.domain.models.MVEntity4DataNegotiation;
import es.in2.desmos.domain.utils.EndpointsConstants;
import es.in2.desmos.infrastructure.configs.EndpointsConfig;
import es.in2.desmos.infrastructure.security.M2MAccessTokenProvider;
import es.in2.desmos.objectmothers.MVEntity4DataNegotiationMother;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.json.JSONException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscoverySyncWebClientTest {
    @Mock
    private M2MAccessTokenProvider mockTokenProvider;

    @Mock
    private EndpointsConfig endpointsConfig;

    private String p2pDiscoveryEndpoint;

    @InjectMocks
    private DiscoverySyncWebClientImpl discoverySyncWebClient;

    private MockWebServer mockWebServer;


    @BeforeEach
    void setUp() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        p2pDiscoveryEndpoint = "/api/v2"+ EndpointsConstants.P2P_SYNC_PREFIX + EndpointsConstants.P2P_DISCOVERY_SYNC;
        WebClient webClient = WebClient.builder().baseUrl(mockWebServer.url("/").toString()).build();
        discoverySyncWebClient = new DiscoverySyncWebClientImpl(webClient, mockTokenProvider, endpointsConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        mockWebServer.shutdown();
    }

    @Test
    void   makeRequest_shouldReturnFluxOfEntityValues() throws Exception {
        String mockAccessToken = "mock-access-token";
        when(mockTokenProvider.getM2MAccessToken()).thenReturn(Mono.just(mockAccessToken));
        when(endpointsConfig.p2pDiscoveryEndpoint()).thenReturn(p2pDiscoveryEndpoint);

        String responseBody = """
                {
                     "external_minimum_viable_entities_for_data_negotiation_list": [
                         {
                             "endDateTime": null,
                             "hash": "6c029d03a53c522aec0cfca64dddb56471c0cb081241148af408e5a23066c78e",
                             "hashlink": "41e7dce7f1a6a1aba71295462c1703308161b0d21dc833755b8fbc8f5103b0b2",
                             "id": "urn:ngsi-ld:product-offering:995c59a5-384a-46a1-bc35-f84d215a86f1",
                             "lastUpdate": "2024-09-13T08:13:11.526074557Z",
                             "lifecycleStatus": "Launched",
                             "type": "product-offering",
                             "validFor": "2024-09-13T08:13:10.183Z",
                             "version": "0.1"
                         }
                     ],
                     "issuer": "https://desmos.dome-marketplace-lcl.org"
                 }
                """;

        mockWebServer.enqueue(new MockResponse()
                .setBody(responseBody)
                .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_NDJSON_VALUE));

        Mono<String> url = Mono.just(mockWebServer.url("/").toString());
        Flux<MVEntity4DataNegotiation> result = discoverySyncWebClient.makeRequest("process1", url, "X-Issuer" ,MVEntity4DataNegotiationMother.list1And2());

        StepVerifier.create(result)
                .expectNextCount(1)
                .verifyComplete();

        var recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getPath()).isEqualTo(p2pDiscoveryEndpoint);
        assertThat(recordedRequest.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + mockAccessToken);
        assertThat(recordedRequest.getHeader(HttpHeaders.CONTENT_TYPE)).isEqualTo("application/x-ndjson");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 500})
    void itShouldThrowExceptionWhenStatusIs4xxOr5xx(int responseCode) throws IOException {
        try (MockWebServer mockWebServer1 = new MockWebServer()) {
            mockWebServer1.enqueue(new MockResponse()
                    .setResponseCode(responseCode)
                    .setBody("error-body")); // opcional, mejor poner algo

            String mockAccessToken = "mock-access-token";
            when(mockTokenProvider.getM2MAccessToken()).thenReturn(Mono.just(mockAccessToken));

            Mono<String> url = Mono.just(mockWebServer1.url("/").toString());
            Flux<MVEntity4DataNegotiation> result = discoverySyncWebClient.makeRequest(
                    "process1",
                    url,
                    "X-Issuer",
                    MVEntity4DataNegotiationMother.list1And2()
            );

            StepVerifier
                    .create(result)
                    .expectError(DiscoverySyncException.class)
                    .verify();
        } catch (JSONException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void itShouldThrowDiscoverySyncExceptionWhenStatusIs404() throws IOException {
        try (MockWebServer mockWebServer1 = new MockWebServer()) {
            mockWebServer1.enqueue(new MockResponse()
                    .setResponseCode(404)
                    .setBody("not found"));

            String mockAccessToken = "mock-access-token";
            when(mockTokenProvider.getM2MAccessToken()).thenReturn(Mono.just(mockAccessToken));

            Mono<String> url = Mono.just(mockWebServer1.url("/").toString());
            Flux<MVEntity4DataNegotiation> result = discoverySyncWebClient.makeRequest(
                    "process-404",
                    url,
                    "issuer-404",
                    MVEntity4DataNegotiationMother.list1And2()
            );

            StepVerifier
                    .create(result)
                    .expectErrorSatisfies(error -> {
                        assertThat(error)
                                .isInstanceOf(DiscoverySyncException.class)
                                .hasMessageContaining("404") // confirma que el mensaje contiene el código
                                .hasMessageContaining("issuer-404"); // confirma que pasó correctamente el issuer
                    })
                    .verify();
        } catch (JSONException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }


}