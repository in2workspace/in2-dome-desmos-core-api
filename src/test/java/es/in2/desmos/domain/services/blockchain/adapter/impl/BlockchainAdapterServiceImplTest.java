package es.in2.desmos.domain.services.blockchain.adapter.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import es.in2.desmos.domain.models.BlockchainTxPayload;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.infrastructure.configs.properties.DLTAdapterProperties;
import io.netty.handler.timeout.ReadTimeoutException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;

import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        lenient().when(dltAdapterProperties.responseTimeout()).thenReturn(Duration.ofSeconds(120));
        lenient().when(dltAdapterProperties.publicationTimeout()).thenReturn(Duration.ofMinutes(10));
        lenient().when(apiConfig.clientHttpConnector(any())).thenReturn(new ReactorClientHttpConnector());

        blockchainAdapterService = new BlockchainAdapterServiceImpl(dltAdapterProperties, apiConfig);
        blockchainAdapterService.init();
        ReflectionTestUtils.setField(blockchainAdapterService, "publicationRetryBackoff", Duration.ofMillis(1));
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
    void itShouldNotRetryWhenDltRejectsThePublicationWithAnErrorBody() {
        // The adapter may already have processed it: sending it again could publish a second transaction
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("dlt-adapter-error"));

        BlockchainTxPayload payload = blockchainTxPayload();

        // The failure is propagated so that the caller does not record the entity as PUBLISHED
        StepVerifier.create(blockchainAdapterService.postTxPayload("process1", payload))
                .expectError(WebClientResponseException.class)
                .verify();

        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    void itShouldRetryWhenDltAdapterIsUnavailable() {
        for (int i = 0; i < 4; i++) {
            mockWebServer.enqueue(new MockResponse().setResponseCode(503));
        }

        BlockchainTxPayload payload = blockchainTxPayload();

        // The failure is propagated so that the caller does not record the entity as PUBLISHED
        StepVerifier.create(blockchainAdapterService.postTxPayload("process1", payload))
                .expectError(WebClientResponseException.class)
                .verify();

        assertThat(mockWebServer.getRequestCount()).isEqualTo(4);
    }

    @Test
    void itShouldWaitBetweenRetries() {
        ReflectionTestUtils.setField(blockchainAdapterService, "publicationRetryBackoff", Duration.ofMillis(200));
        mockWebServer.enqueue(new MockResponse().setResponseCode(503));
        mockWebServer.enqueue(new MockResponse().setResponseCode(503));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        long startNanos = System.nanoTime();
        StepVerifier.create(blockchainAdapterService.postTxPayload("process1", blockchainTxPayload()))
                .verifyComplete();

        // Backoff of 200ms then 400ms, each with up to 50% jitter: at least 300ms in total
        assertThat(Duration.ofNanos(System.nanoTime() - startNanos)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
        assertThat(mockWebServer.getRequestCount()).isEqualTo(3);
    }

    @Test
    void itShouldNotRetryWhenTheDltAdapterDoesNotAnswerInTimeAndReportTheOutcomeAsUnknown() {
        // The publication timeout applies, not the shorter response timeout of the client
        when(apiConfig.clientHttpConnector(any())).thenReturn(new ReactorClientHttpConnector(
                HttpClient.create().responseTimeout(Duration.ofMillis(100))));
        when(dltAdapterProperties.publicationTimeout()).thenReturn(Duration.ofMillis(800));
        blockchainAdapterService.init();
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setHeadersDelay(400, TimeUnit.MILLISECONDS));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setHeadersDelay(2, TimeUnit.SECONDS));
        Logger logger = (Logger) LoggerFactory.getLogger(BlockchainAdapterServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            StepVerifier.create(blockchainAdapterService.postTxPayload("process1", blockchainTxPayload()))
                    .verifyComplete();
            assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains("outcome unknown"));

            StepVerifier.create(blockchainAdapterService.postTxPayload("process1", blockchainTxPayload()))
                    .expectError(WebClientRequestException.class)
                    .verify();
            assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.ERROR
                    && event.getFormattedMessage().contains("DLT publication outcome unknown for entityId="));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
    }

    @Test
    void itShouldOnlyReportAsUnknownThePublicationsThatReachedTheAdapterWithoutAnswer() {
        URI uri = URI.create("http://dlt-adapter:8080");
        assertThat(BlockchainAdapterServiceImpl.isPublicationOutcomeUnknown(new WebClientRequestException(
                ReadTimeoutException.INSTANCE, HttpMethod.POST, uri, new HttpHeaders()))).isTrue();
        assertThat(BlockchainAdapterServiceImpl.isPublicationOutcomeUnknown(new WebClientRequestException(
                new ConnectException("Connection refused"), HttpMethod.POST, uri, new HttpHeaders()))).isFalse();
        assertThat(BlockchainAdapterServiceImpl.isPublicationOutcomeUnknown(
                WebClientResponseException.create(500, "Internal Server Error", new HttpHeaders(), new byte[0], null))).isFalse();
    }

    @Test
    void itShouldOnlyRetryPublicationsThatCertainlyDidNotReachTheAdapter() {
        URI uri = URI.create("http://dlt-adapter:8080");
        assertThat(BlockchainAdapterServiceImpl.isSafeToRetryPublication(new WebClientRequestException(
                new ConnectException("Connection refused"), HttpMethod.POST, uri, new HttpHeaders()))).isTrue();
        assertThat(BlockchainAdapterServiceImpl.isSafeToRetryPublication(new WebClientRequestException(
                ReadTimeoutException.INSTANCE, HttpMethod.POST, uri, new HttpHeaders()))).isFalse();
        assertThat(BlockchainAdapterServiceImpl.isSafeToRetryPublication(new IllegalStateException("boom"))).isFalse();
    }

    @Test
    void itShouldBuildTheWebClientWithTheDltAdapterResponseTimeout() {
        verify(apiConfig).clientHttpConnector(Duration.ofSeconds(120));
    }

    @Test
    void dltAdapterPropertiesUsesDefaultResponseTimeoutWhenNotConfigured() {
        DLTAdapterProperties properties = new DLTAdapterProperties("digitelts", null, "http://dlt-adapter:8080", null, null, null);

        assertThat(properties.responseTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(properties.publicationTimeout()).isEqualTo(Duration.ofMinutes(10));
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
