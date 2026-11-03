package es.in2.desmos.application.runners;

import ch.qos.logback.classic.Level;
import es.in2.desmos.application.workflows.DataSyncWorkflow;
import es.in2.desmos.application.workflows.PublishWorkflow;
import es.in2.desmos.application.workflows.SubscribeWorkflow;
import es.in2.desmos.domain.exceptions.P2PDataSyncException;
import es.in2.desmos.domain.exceptions.SubscriptionCreationException;
import es.in2.desmos.domain.exceptions.SubscriptionLookupException;
import es.in2.desmos.domain.exceptions.SubscriptionUpdateException;
import es.in2.desmos.domain.models.BlockchainSubscription;
import es.in2.desmos.domain.services.api.QueueService;
import es.in2.desmos.domain.services.blockchain.BlockchainListenerService;
import es.in2.desmos.domain.services.broker.BrokerListenerService;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.infrastructure.configs.BlockchainConfig;
import es.in2.desmos.infrastructure.configs.BrokerConfig;
import es.in2.desmos.infrastructure.configs.LearCredentialMachineConfig;
import es.in2.desmos.infrastructure.configs.TrustFrameworkConfig;
import es.in2.desmos.testutils.LogCapture;
import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import es.in2.desmos.domain.exceptions.DiscoverySyncException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApplicationRunnerTests {

    @Mock
    private ApiConfig apiConfig;

    @Mock
    private BrokerConfig brokerConfig;

    @Mock
    private BlockchainConfig blockchainConfig;

    @Mock
    private DataSyncWorkflow dataSyncWorkflow;

    @Mock
    private PublishWorkflow publishWorkflow;

    @Mock
    private SubscribeWorkflow subscribeWorkflow;

    @Mock
    private BrokerListenerService brokerListenerService;

    @Mock
    private BlockchainListenerService blockchainListenerService;

    @Mock
    private TrustFrameworkConfig trustFrameworkConfig;

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private LearCredentialMachineConfig learCredentialMachineConfig;

    @Mock
    private QueueService pendingPublishEventsQueue;

    @Mock
    private QueueService pendingSubscribeEventsQueue;

    private ApplicationRunner applicationRunner;

    @BeforeEach
    void setUp() {
        applicationRunner = newApplicationRunner(applicationContext);
    }

    private ApplicationRunner newApplicationRunner(ApplicationContext context) {
        return new ApplicationRunner(apiConfig, brokerConfig, blockchainConfig, brokerListenerService,
                blockchainListenerService, trustFrameworkConfig, dataSyncWorkflow, publishWorkflow, subscribeWorkflow,
                context, learCredentialMachineConfig, pendingPublishEventsQueue, pendingSubscribeEventsQueue, "dev");
    }

    @Test
    void whenApplicationIsReady_thenSetBrokerSubscriptionAndBlockchainSubscription() {
        // Arrange
        when(brokerListenerService.createSubscription(anyString(), any())).thenReturn(Mono.empty());
        when(blockchainListenerService.createSubscription(anyString(), any(BlockchainSubscription.class))).thenReturn(Mono.empty());
        when(trustFrameworkConfig.initialize()).thenReturn(Mono.empty());
        when(dataSyncWorkflow.startDataSyncWorkflow(anyString())).thenReturn(Flux.empty());
        when(publishWorkflow.startPublishWorkflow()).thenReturn(Flux.empty());
        when(subscribeWorkflow.startSubscribeWorkflow()).thenReturn(Flux.empty());
        when(apiConfig.getCurrentEnvironment()).thenReturn("dev");
        // Act
        mock(ApplicationReadyEvent.class);
        //Assert
        StepVerifier.create(applicationRunner.onApplicationReady()).verifyComplete();
    }

    @Test
    void whenDisposeIsActive() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        // Arrange
        ApplicationRunner localApplicationRunner  = newApplicationRunner(applicationContext);
        Method disposeIfActive = ApplicationRunner.class.getDeclaredMethod("disposeIfActive", Disposable.class);
        disposeIfActive.setAccessible(true);
        Disposable disposable = mock(Disposable.class);
        disposeIfActive.invoke(localApplicationRunner , disposable);
        when(brokerListenerService.createSubscription(anyString(), any())).thenReturn(Mono.empty());
        when(blockchainListenerService.createSubscription(anyString(), any(BlockchainSubscription.class))).thenReturn(Mono.empty());
        when(trustFrameworkConfig.initialize()).thenReturn(Mono.empty());
        when(dataSyncWorkflow.startDataSyncWorkflow(anyString())).thenReturn(Flux.empty());
        when(publishWorkflow.startPublishWorkflow()).thenReturn(Flux.empty());
        when(subscribeWorkflow.startSubscribeWorkflow()).thenReturn(Flux.empty());
        when(apiConfig.getCurrentEnvironment()).thenReturn("dev");
        // Act
        mock(ApplicationReadyEvent.class);
        //Assert
        StepVerifier.create(localApplicationRunner .onApplicationReady()).verifyComplete();
    }

    @Test
    void whenInitialDataSyncFails_thenPubSubQueuesAreStartedAnyway() {
        when(brokerListenerService.createSubscription(anyString(), any())).thenReturn(Mono.empty());
        when(blockchainListenerService.createSubscription(anyString(), any(BlockchainSubscription.class))).thenReturn(Mono.empty());
        when(trustFrameworkConfig.initialize()).thenReturn(Mono.empty());
        when(dataSyncWorkflow.startDataSyncWorkflow(anyString()))
                .thenReturn(Flux.error(new DiscoverySyncException("Error 504 occurred while discovery sync.")));
        when(publishWorkflow.startPublishWorkflow()).thenReturn(Flux.never());
        when(subscribeWorkflow.startSubscribeWorkflow()).thenReturn(Flux.never());
        when(apiConfig.getCurrentEnvironment()).thenReturn("dev");

        StepVerifier.create(applicationRunner.onApplicationReady()).verifyComplete();

        verify(publishWorkflow).startPublishWorkflow();
        verify(subscribeWorkflow).startSubscribeWorkflow();
    }

    @Test
    void whenBrokerIsNotReachableYet_thenBrokerSubscriptionIsRetried() {
        AtomicInteger attempts = new AtomicInteger();
        when(brokerListenerService.createSubscription(anyString(), any())).thenReturn(Mono.defer(() ->
                attempts.incrementAndGet() < 3
                        ? Mono.error(new WebClientRequestException(new ConnectException("Connection refused"),
                        HttpMethod.GET, URI.create("http://scorpio:9090"), new HttpHeaders()))
                        : Mono.empty()));
        when(blockchainListenerService.createSubscription(anyString(), any(BlockchainSubscription.class))).thenReturn(Mono.empty());
        when(trustFrameworkConfig.initialize()).thenReturn(Mono.empty());
        when(dataSyncWorkflow.startDataSyncWorkflow(anyString())).thenReturn(Flux.empty());
        when(publishWorkflow.startPublishWorkflow()).thenReturn(Flux.never());
        when(subscribeWorkflow.startSubscribeWorkflow()).thenReturn(Flux.never());
        when(apiConfig.getCurrentEnvironment()).thenReturn("dev");
        ApplicationRunner spiedRunner = spy(applicationRunner);

        StepVerifier.withVirtualTime(spiedRunner::onApplicationReady)
                .thenAwait(Duration.ofSeconds(10))
                .verifyComplete();

        assertEquals(3, attempts.get());
        verify(spiedRunner, never()).exit(anyInt());
    }

    @Test
    void whenStartupStepFailsWithNonTransientError_thenItIsNotRetriedAndAppExitsWithCode1() {
        AtomicInteger attempts = new AtomicInteger();
        when(brokerListenerService.createSubscription(anyString(), any())).thenReturn(Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(new IllegalStateException("Invalid subscription"));
        }));
        when(dataSyncWorkflow.startDataSyncWorkflow(anyString())).thenReturn(Flux.empty());
        ApplicationRunner spiedRunner = spy(applicationRunner);
        doNothing().when(spiedRunner).exit(anyInt());

        StepVerifier.create(spiedRunner.onApplicationReady())
                .expectError(IllegalStateException.class)
                .verify();

        verify(spiedRunner, timeout(5000)).exit(1);
        assertEquals(1, attempts.get());
    }

    @Test
    void whenStartupStepFailsBecauseContextIsClosing_thenAppDoesNotExit() {
        ConfigurableApplicationContext closingContext = mock(ConfigurableApplicationContext.class);
        when(closingContext.isActive()).thenReturn(false);
        when(brokerListenerService.createSubscription(anyString(), any()))
                .thenReturn(Mono.error(new IllegalStateException("Context has been closed already")));
        when(dataSyncWorkflow.startDataSyncWorkflow(anyString())).thenReturn(Flux.empty());
        ApplicationRunner localApplicationRunner = spy(newApplicationRunner(closingContext));

        StepVerifier.create(localApplicationRunner.onApplicationReady())
                .expectError(IllegalStateException.class)
                .verify();

        verify(localApplicationRunner, after(500).never()).exit(anyInt());
    }

    @Test
    void whenStartupFinishes_thenNodeConfigurationAndReadySummaryAreLogged() {
        when(brokerListenerService.createSubscription(anyString(), any())).thenReturn(Mono.empty());
        when(blockchainListenerService.createSubscription(anyString(), any(BlockchainSubscription.class))).thenReturn(Mono.empty());
        when(trustFrameworkConfig.initialize()).thenReturn(Mono.empty());
        when(dataSyncWorkflow.startDataSyncWorkflow(anyString())).thenReturn(Flux.empty());
        when(publishWorkflow.startPublishWorkflow()).thenReturn(Flux.never());
        when(subscribeWorkflow.startSubscribeWorkflow()).thenReturn(Flux.never());
        when(apiConfig.getCurrentEnvironment()).thenReturn("dev");
        when(apiConfig.getExternalDomain()).thenReturn("https://node.example.org/desmos");
        when(apiConfig.getOrganizationId()).thenReturn("VATES-B12345678");
        when(learCredentialMachineConfig.getLearCredentialMachineExpiration())
                .thenReturn(Optional.of(Instant.parse("2027-01-31T00:00:00Z")));
        when(pendingPublishEventsQueue.pendingEvents()).thenReturn(2L);
        when(pendingSubscribeEventsQueue.pendingEvents()).thenReturn(5L);

        try (LogCapture logCapture = LogCapture.of(ApplicationRunner.class)) {
            StepVerifier.create(applicationRunner.onApplicationReady()).verifyComplete();

            List<String> infoLines = logCapture.messages(Level.INFO);
            assertThat(infoLines).anySatisfy(line -> assertThat(line).endsWith(" - profile=dev "
                    + "externalDomain=https://node.example.org/desmos operator=VATES-B12345678 "
                    + "learCredentialMachineExpiration=2027-01-31T00:00:00Z"));
            assertThat(infoLines).anySatisfy(line -> assertThat(line).startsWith("[init] ProcessID: ")
                    .contains(" - READY startup=")
                    .endsWith(" sync=completed pendingPublish=2 pendingSubscribe=5"));
            assertThat(infoLines).allSatisfy(line -> assertThat(line).startsWith("[init] "));
        }
    }

    @Test
    void whenBrokerAdapterWrapsAConnectionError_thenBrokerSubscriptionIsRetried() {
        AtomicInteger attempts = new AtomicInteger();
        when(brokerListenerService.createSubscription(anyString(), any())).thenReturn(Mono.defer(() ->
                attempts.incrementAndGet() < 2
                        ? Mono.error(new SubscriptionCreationException("Error fetching subscriptions from broker",
                        new WebClientRequestException(new ConnectException("Connection refused"),
                                HttpMethod.GET, URI.create("http://scorpio:9090"), new HttpHeaders())))
                        : Mono.empty()));
        when(blockchainListenerService.createSubscription(anyString(), any(BlockchainSubscription.class))).thenReturn(Mono.empty());
        when(trustFrameworkConfig.initialize()).thenReturn(Mono.empty());
        when(dataSyncWorkflow.startDataSyncWorkflow(anyString())).thenReturn(Flux.empty());
        when(publishWorkflow.startPublishWorkflow()).thenReturn(Flux.never());
        when(subscribeWorkflow.startSubscribeWorkflow()).thenReturn(Flux.never());
        when(apiConfig.getCurrentEnvironment()).thenReturn("dev");
        ApplicationRunner spiedRunner = spy(applicationRunner);

        StepVerifier.withVirtualTime(spiedRunner::onApplicationReady)
                .thenAwait(Duration.ofSeconds(10))
                .verifyComplete();

        assertEquals(2, attempts.get());
        verify(spiedRunner, never()).exit(anyInt());
    }

    @Test
    void whenTheTrustedListNeverAnswers_thenEachAttemptTimesOutAndAppExitsAfterTheRetries() {
        AtomicInteger attempts = new AtomicInteger();
        when(brokerListenerService.createSubscription(anyString(), any())).thenReturn(Mono.empty());
        when(blockchainListenerService.createSubscription(anyString(), any(BlockchainSubscription.class))).thenReturn(Mono.empty());
        when(apiConfig.getCurrentEnvironment()).thenReturn("dev");
        when(trustFrameworkConfig.initialize()).thenReturn(Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.never();
        }));
        when(dataSyncWorkflow.startDataSyncWorkflow(anyString())).thenReturn(Flux.empty());
        ApplicationRunner spiedRunner = spy(applicationRunner);
        doNothing().when(spiedRunner).exit(anyInt());

        StepVerifier.withVirtualTime(spiedRunner::onApplicationReady)
                .thenAwait(Duration.ofMinutes(15))
                .expectErrorMatches(error -> error instanceof TimeoutException
                        && error.getMessage().startsWith("Access Node Trusted List Getting did not finish"))
                .verify(Duration.ofSeconds(30));

        assertEquals(4, attempts.get());
        verify(spiedRunner, timeout(5000)).exit(1);
    }

    @Test
    void whenInitialDataSyncIsIncomplete_thenReadySummaryReportsTheFailure() {
        when(brokerListenerService.createSubscription(anyString(), any())).thenReturn(Mono.empty());
        when(blockchainListenerService.createSubscription(anyString(), any(BlockchainSubscription.class))).thenReturn(Mono.empty());
        when(trustFrameworkConfig.initialize()).thenReturn(Mono.empty());
        when(dataSyncWorkflow.startDataSyncWorkflow(anyString())).thenReturn(Flux.error(new P2PDataSyncException(
                "P2P Data Synchronization incomplete: 0 of 16 entity types failed [], 1 of 1 genesis nodes failed [https://genesis.org]")));
        when(publishWorkflow.startPublishWorkflow()).thenReturn(Flux.never());
        when(subscribeWorkflow.startSubscribeWorkflow()).thenReturn(Flux.never());
        when(apiConfig.getCurrentEnvironment()).thenReturn("dev");

        try (LogCapture logCapture = LogCapture.of(ApplicationRunner.class)) {
            StepVerifier.create(applicationRunner.onApplicationReady()).verifyComplete();

            assertThat(logCapture.messages(Level.INFO)).anySatisfy(line -> assertThat(line)
                    .contains(" - READY startup=")
                    .contains(" sync=failed (P2P Data Synchronization incomplete: 0 of 16 entity types failed [], "
                            + "1 of 1 genesis nodes failed [https://genesis.org]) "));
            assertThat(logCapture.messages(Level.WARN)).singleElement().satisfies(line -> assertThat(line)
                    .contains(" - Initial Data Sync incomplete: P2P Data Synchronization incomplete"));
            assertThat(logCapture.messages(Level.ERROR)).noneMatch(line -> line.contains("Error initializing Data Sync"));
        }
    }

    @Test
    void whenNodeConfigurationCannotBeLogged_thenStartupGoesOn() {
        when(brokerListenerService.createSubscription(anyString(), any())).thenReturn(Mono.empty());
        when(blockchainListenerService.createSubscription(anyString(), any(BlockchainSubscription.class))).thenReturn(Mono.empty());
        when(trustFrameworkConfig.initialize()).thenReturn(Mono.empty());
        when(dataSyncWorkflow.startDataSyncWorkflow(anyString())).thenReturn(Flux.empty());
        when(publishWorkflow.startPublishWorkflow()).thenReturn(Flux.never());
        when(subscribeWorkflow.startSubscribeWorkflow()).thenReturn(Flux.never());
        when(apiConfig.getCurrentEnvironment()).thenReturn("dev");
        when(learCredentialMachineConfig.getLearCredentialMachineExpiration()).thenThrow(new IllegalStateException("unexpected claim"));
        ApplicationRunner spiedRunner = spy(applicationRunner);

        StepVerifier.create(spiedRunner.onApplicationReady()).verifyComplete();

        verify(spiedRunner, never()).exit(anyInt());
        verify(publishWorkflow).startPublishWorkflow();
    }

    @Test
    void whenBlockchainSubscriptionTimesOut_thenItIsNotRetriedToAvoidDuplicateSubscriptions() {
        AtomicInteger attempts = new AtomicInteger();
        when(brokerListenerService.createSubscription(anyString(), any())).thenReturn(Mono.empty());
        when(apiConfig.getCurrentEnvironment()).thenReturn("dev");
        when(blockchainListenerService.createSubscription(anyString(), any(BlockchainSubscription.class))).thenReturn(Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.never();
        }));
        when(dataSyncWorkflow.startDataSyncWorkflow(anyString())).thenReturn(Flux.empty());
        ApplicationRunner spiedRunner = spy(applicationRunner);
        doNothing().when(spiedRunner).exit(anyInt());

        StepVerifier.withVirtualTime(spiedRunner::onApplicationReady)
                .thenAwait(Duration.ofMinutes(15))
                .expectError(TimeoutException.class)
                .verify(Duration.ofSeconds(30));

        assertEquals(1, attempts.get());
        verify(spiedRunner, timeout(5000)).exit(1);
    }

    @Test
    void whenASlowLookupIsFollowedByASlowCreation_thenTheStepIsNotCutByTheAttemptTimeout() {
        AtomicInteger attempts = new AtomicInteger();
        // 50s listing the subscriptions plus 110s creating one, both within their own client timeouts
        when(brokerListenerService.createSubscription(anyString(), any())).thenReturn(Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.delay(Duration.ofSeconds(160)).then();
        }));
        when(blockchainListenerService.createSubscription(anyString(), any(BlockchainSubscription.class))).thenReturn(Mono.empty());
        when(trustFrameworkConfig.initialize()).thenReturn(Mono.empty());
        when(dataSyncWorkflow.startDataSyncWorkflow(anyString())).thenReturn(Flux.empty());
        when(publishWorkflow.startPublishWorkflow()).thenReturn(Flux.never());
        when(subscribeWorkflow.startSubscribeWorkflow()).thenReturn(Flux.never());
        when(apiConfig.getCurrentEnvironment()).thenReturn("dev");
        ApplicationRunner spiedRunner = spy(applicationRunner);

        StepVerifier.withVirtualTime(spiedRunner::onApplicationReady)
                .thenAwait(Duration.ofSeconds(170))
                .expectComplete()
                .verify(Duration.ofSeconds(30));

        assertEquals(1, attempts.get());
        verify(spiedRunner, never()).exit(anyInt());
    }

    @Test
    void whenListingBrokerSubscriptionsTimesOut_thenItIsRetried() {
        AtomicInteger attempts = new AtomicInteger();
        when(brokerListenerService.createSubscription(anyString(), any())).thenReturn(Mono.defer(() ->
                attempts.incrementAndGet() < 2
                        ? Mono.error(new SubscriptionLookupException("Error fetching subscriptions from broker",
                        new WebClientRequestException(ReadTimeoutException.INSTANCE, HttpMethod.GET,
                                URI.create("http://scorpio:9090"), new HttpHeaders())))
                        : Mono.empty()));
        when(blockchainListenerService.createSubscription(anyString(), any(BlockchainSubscription.class))).thenReturn(Mono.empty());
        when(trustFrameworkConfig.initialize()).thenReturn(Mono.empty());
        when(dataSyncWorkflow.startDataSyncWorkflow(anyString())).thenReturn(Flux.empty());
        when(publishWorkflow.startPublishWorkflow()).thenReturn(Flux.never());
        when(subscribeWorkflow.startSubscribeWorkflow()).thenReturn(Flux.never());
        when(apiConfig.getCurrentEnvironment()).thenReturn("dev");
        ApplicationRunner spiedRunner = spy(applicationRunner);

        StepVerifier.withVirtualTime(spiedRunner::onApplicationReady)
                .thenAwait(Duration.ofSeconds(10))
                .expectComplete()
                .verify(Duration.ofSeconds(30));

        assertEquals(2, attempts.get());
        verify(spiedRunner, never()).exit(anyInt());
    }

    @Test
    void whenUpdatingTheBrokerSubscriptionTimesOut_thenItIsRetried() {
        AtomicInteger attempts = new AtomicInteger();
        when(brokerListenerService.createSubscription(anyString(), any())).thenReturn(Mono.defer(() ->
                attempts.incrementAndGet() < 2
                        ? Mono.error(new SubscriptionUpdateException("Error updating subscription in broker",
                        new WebClientRequestException(ReadTimeoutException.INSTANCE, HttpMethod.PATCH,
                                URI.create("http://scorpio:9090"), new HttpHeaders())))
                        : Mono.empty()));
        when(blockchainListenerService.createSubscription(anyString(), any(BlockchainSubscription.class))).thenReturn(Mono.empty());
        when(trustFrameworkConfig.initialize()).thenReturn(Mono.empty());
        when(dataSyncWorkflow.startDataSyncWorkflow(anyString())).thenReturn(Flux.empty());
        when(publishWorkflow.startPublishWorkflow()).thenReturn(Flux.never());
        when(subscribeWorkflow.startSubscribeWorkflow()).thenReturn(Flux.never());
        when(apiConfig.getCurrentEnvironment()).thenReturn("dev");
        ApplicationRunner spiedRunner = spy(applicationRunner);

        StepVerifier.withVirtualTime(spiedRunner::onApplicationReady)
                .thenAwait(Duration.ofSeconds(10))
                .expectComplete()
                .verify(Duration.ofSeconds(30));

        assertEquals(2, attempts.get());
        verify(spiedRunner, never()).exit(anyInt());
    }

    @Test
    void whenCreatingTheBrokerSubscriptionTimesOut_thenItIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        when(brokerListenerService.createSubscription(anyString(), any())).thenReturn(Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(new WebClientRequestException(ReadTimeoutException.INSTANCE, HttpMethod.POST,
                    URI.create("http://scorpio:9090"), new HttpHeaders()));
        }));
        when(dataSyncWorkflow.startDataSyncWorkflow(anyString())).thenReturn(Flux.empty());
        ApplicationRunner spiedRunner = spy(applicationRunner);
        doNothing().when(spiedRunner).exit(anyInt());

        StepVerifier.create(spiedRunner.onApplicationReady())
                .expectError(WebClientRequestException.class)
                .verify(Duration.ofSeconds(30));

        assertEquals(1, attempts.get());
        verify(spiedRunner, timeout(5000)).exit(1);
    }
}
