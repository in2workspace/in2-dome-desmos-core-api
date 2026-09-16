package es.in2.desmos.domain.services.blockchain.adapter.impl;

import es.in2.desmos.domain.models.BlockchainTxPayload;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.infrastructure.configs.properties.DLTAdapterProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class BlockchainAdapterServiceImplTest {

    private static final String PUBLICATION_PATH = "/publication";

    @Mock
    private DLTAdapterProperties dltAdapterProperties;

    @Mock
    private DLTAdapterProperties.DLTAdapterPathProperties dltAdapterPathProperties;

    @Mock
    private ApiConfig apiConfig;

    private BlockchainAdapterServiceImpl blockchainAdapterService;

    private MockWebServer mockWebServer;

    @BeforeEach
    void setUp() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        lenient().when(dltAdapterProperties.externalDomain()).thenReturn(mockWebServer.url("/").toString());
        lenient().when(dltAdapterProperties.paths()).thenReturn(dltAdapterPathProperties);
        lenient().when(dltAdapterPathProperties.publication()).thenReturn(PUBLICATION_PATH);
        lenient().when(apiConfig.getMaxInMemorySize()).thenReturn(DataSize.ofMegabytes(1));

        blockchainAdapterService = new BlockchainAdapterServiceImpl(dltAdapterProperties, apiConfig);
        blockchainAdapterService.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        mockWebServer.shutdown();
    }

    @Test
    void itShouldCompleteWhenDltAcceptsThePublication() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        BlockchainTxPayload payload = blockchainTxPayload();

        StepVerifier.create(blockchainAdapterService.postTxPayload("process1", payload))
                .verifyComplete();

        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        assertThat(mockWebServer.takeRequest().getPath()).isEqualTo(PUBLICATION_PATH);
    }

    @Test
    void itShouldLogAndRetryWhenDltRejectsThePublicationWithAnErrorBody() {
        for (int i = 0; i < 4; i++) {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("dlt-adapter-error"));
        }

        BlockchainTxPayload payload = blockchainTxPayload();

        StepVerifier.create(blockchainAdapterService.postTxPayload("process1", payload))
                .verifyComplete();

        assertThat(mockWebServer.getRequestCount()).isEqualTo(4);
    }

    @Test
    void itShouldLogAndRetryWhenDltRejectsThePublicationWithAnEmptyBody() {
        for (int i = 0; i < 4; i++) {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
        }

        BlockchainTxPayload payload = blockchainTxPayload();

        StepVerifier.create(blockchainAdapterService.postTxPayload("process1", payload))
                .verifyComplete();

        assertThat(mockWebServer.getRequestCount()).isEqualTo(4);
    }

    private BlockchainTxPayload blockchainTxPayload() {
        return BlockchainTxPayload.builder()
                .eventType("CREATED")
                .organizationIdentifier("VATFR-12345")
                .entityId("urn:ngsi-ld:product-offering:1")
                .previousEntityHashLink(null)
                .dataLocation("urn:ngsi-ld:product-offering:1")
                .metadata(null)
                .build();
    }

}
