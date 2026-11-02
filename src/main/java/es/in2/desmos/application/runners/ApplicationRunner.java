package es.in2.desmos.application.runners;

import es.in2.desmos.application.workflows.DataSyncWorkflow;
import es.in2.desmos.application.workflows.PublishWorkflow;
import es.in2.desmos.application.workflows.SubscribeWorkflow;
import es.in2.desmos.domain.exceptions.P2PDataSyncException;
import es.in2.desmos.domain.exceptions.RequestErrorException;
import es.in2.desmos.domain.exceptions.SubscriptionLookupException;
import es.in2.desmos.domain.exceptions.SubscriptionUpdateException;
import es.in2.desmos.domain.models.BlockchainSubscription;
import es.in2.desmos.domain.models.BrokerSubscription;
import es.in2.desmos.domain.services.api.QueueService;
import es.in2.desmos.domain.services.blockchain.BlockchainListenerService;
import es.in2.desmos.domain.services.broker.BrokerListenerService;
import es.in2.desmos.domain.utils.LogUtils;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.infrastructure.configs.BlockchainConfig;
import es.in2.desmos.infrastructure.configs.BrokerConfig;
import es.in2.desmos.infrastructure.configs.LearCredentialMachineConfig;
import es.in2.desmos.infrastructure.configs.TrustFrameworkConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static es.in2.desmos.domain.utils.ApplicationConstants.SUBSCRIPTION_ID_PREFIX;
import static es.in2.desmos.domain.utils.ApplicationConstants.SUBSCRIPTION_TYPE;
import static es.in2.desmos.domain.utils.ApplicationUtils.anyCause;
import static es.in2.desmos.domain.utils.ApplicationUtils.getEnvironmentMetadata;
import static es.in2.desmos.domain.utils.ApplicationUtils.isSafeToResend;
import static es.in2.desmos.domain.utils.LogUtils.INIT;

@Slf4j
@Configuration
@RequiredArgsConstructor
// Enabled by default, so production and the two-node replication tests are unaffected. Integration
// tests that only exercise a repository or a single service switch this off: onApplicationReady()
// returns its Mono instead of blocking on it, so the whole startup chain (broker/blockchain
// subscription, trusted list, initial P2P sync, then the pub-sub queue consumers) runs on reactor
// threads concurrently with the test methods -- and that sync writes audit records, see
// P2PDataSyncJobImpl.createLocalMvEntitiesByType.
@ConditionalOnProperty(name = "application.runner.enabled", matchIfMissing = true)
public class ApplicationRunner {

    private final ApiConfig apiConfig;
    private final BrokerConfig brokerConfig;
    private final BlockchainConfig blockchainConfig;
    private final BrokerListenerService brokerListenerService;
    private final BlockchainListenerService blockchainListenerService;
    private final TrustFrameworkConfig trustFrameworkConfig;
    private final DataSyncWorkflow dataSyncWorkflow;
    private final PublishWorkflow publishWorkflow;
    private final SubscribeWorkflow subscribeWorkflow;
    private final ApplicationContext applicationContext;
    private final LearCredentialMachineConfig learCredentialMachineConfig;
    private final QueueService pendingPublishEventsQueue;
    private final QueueService pendingSubscribeEventsQueue;
    private final AtomicBoolean isQueueAuthorizedForEmit = new AtomicBoolean(false);
    private final String getCurrentEnvironment;
    private Disposable publishQueueDisposable;
    private Disposable subscribeQueueDisposable;

    private static final int STARTUP_STEP_MAX_RETRIES = 3;
    private static final Duration STARTUP_STEP_RETRY_DELAY = Duration.ofSeconds(2);
    // Bounds each attempt, including any retries the step does internally (e.g. the trusted list download),
    // so a dependency that accepts the connection and never answers cannot block startup for long. A subscription
    // step is a lookup with its own timeout (SubscriptionLookupException.TIMEOUT, 60s) followed by the creation
    // (10s to connect plus the 120s response timeout of the broker and the DLT adapter), so a slow creation is cut
    // by the client and not by this timeout. Downloading the trusted list (shared WebClient, 300s, retried 3 times)
    // can still be cut here; it is idempotent and retried, and the error names the step.
    private static final Duration STARTUP_STEP_ATTEMPT_TIMEOUT = Duration.ofSeconds(200);

    @EventListener(ApplicationReadyEvent.class)
    public Mono<Void> onApplicationReady() {
        String processId = UUID.randomUUID().toString();
        return Mono.defer(() -> setBrokerSubscription(processId))
                .timeout(STARTUP_STEP_ATTEMPT_TIMEOUT, stepTimedOut("Broker Subscription"))
                .retryWhen(startupStepRetry(processId, "Broker Subscription", ApplicationRunner::isSubscriptionStepRetryable))
                .doFirst(() -> {
                    log.info("{} ProcessID: {} - Starting configuration initialization", INIT, processId);
                    logNodeConfiguration(processId);
                })
                .doOnError(error -> finishApplication("Broker Subscription", error))
                .then(Mono.defer(() -> setBlockchainSubscription(processId))
                        .timeout(STARTUP_STEP_ATTEMPT_TIMEOUT, stepTimedOut("Blockchain Subscription"))
                        .retryWhen(startupStepRetry(processId, "Blockchain Subscription", ApplicationRunner::isSubscriptionStepRetryable))
                        .doOnError(error -> finishApplication("Blockchain Subscription", error)))
                .then(Mono.defer(() -> getTrustedAccessNodesList(processId))
                        .timeout(STARTUP_STEP_ATTEMPT_TIMEOUT, stepTimedOut("Access Node Trusted List Getting"))
                        .retryWhen(startupStepRetry(processId, "Access Node Trusted List Getting", ApplicationRunner::isTransientStartupError))
                        .doOnError(error -> finishApplication("Access Node Trusted List Getting", error)))
                .thenMany(initializeDataSync(processId)
                        .onErrorResume(error -> {
                            if (error instanceof P2PDataSyncException) {
                                // Each failed type and node is already logged in detail by the P2P sync
                                log.warn("{} ProcessID: {} - Initial Data Sync incomplete: {}", INIT, processId, error.getMessage());
                            } else {
                                log.error("{} ProcessID: {} - Error initializing Data Sync: {}", INIT, processId, error.getMessage(), error);
                            }
                            return Flux.empty();
                        }))
                .then();
    }

    // Spring's @Retryable never applied here: these methods are private and called from this class, so no
    // proxy intercepts them, and they return a Mono that fails on subscription, not by throwing. The retry
    // is done with Reactor instead, with a policy per step (see isSubscriptionStepRetryable and
    // isTransientStartupError); errors are also recognised when wrapped by an adapter exception.
    private static Mono<Void> stepTimedOut(String step) {
        return Mono.error(() -> new TimeoutException(step + " did not finish within " + STARTUP_STEP_ATTEMPT_TIMEOUT.toSeconds() + " s"));
    }

    private Retry startupStepRetry(String processId, String step, Predicate<Throwable> retryable) {
        return Retry.fixedDelay(STARTUP_STEP_MAX_RETRIES, STARTUP_STEP_RETRY_DELAY)
                .filter(retryable)
                .doBeforeRetry(retrySignal -> log.warn("{} ProcessID: {} - {} failed (attempt {} of {}), retrying in {} s: {}",
                        INIT, processId, step, retrySignal.totalRetries() + 1, STARTUP_STEP_MAX_RETRIES + 1,
                        STARTUP_STEP_RETRY_DELAY.toSeconds(), retrySignal.failure().getMessage()))
                .onRetryExhaustedThrow((retrySpec, retrySignal) -> retrySignal.failure());
    }

    // For the broker and blockchain subscription steps. Listing the existing subscriptions is read-only and updating
    // one (a PATCH with its full definition) is idempotent: both are retried on any transient failure. Creating one
    // is not: a request that timed out may still be processed after
    // the next attempt's check, creating a duplicate. It is only retried when the request certainly did not arrive
    // or the dependency answered 503; otherwise the node exits and the check runs again on restart.
    private static boolean isSubscriptionStepRetryable(Throwable error) {
        if (anyCause(error, current -> current instanceof SubscriptionLookupException || current instanceof SubscriptionUpdateException)) {
            return isTransientStartupError(error);
        }
        return isSafeToResend(error);
    }

    // For idempotent steps (reading the trusted list): any transient failure, timeouts included, is retried.
    private static boolean isTransientStartupError(Throwable error) {
        return anyCause(error, current -> current instanceof RequestErrorException
                || current instanceof WebClientRequestException
                || current instanceof TimeoutException
                || (current instanceof WebClientResponseException responseException
                && responseException.getStatusCode().is5xxServerError()));
    }

    private Mono<Void> setBrokerSubscription(String processId) {
        // Build Entity Type List to subscribe to
        List<BrokerSubscription.Entity> entities = new ArrayList<>();
        brokerConfig.getEntityTypes().forEach(entityType -> entities.add(BrokerSubscription.Entity.builder()
                .type(entityType).build()));
        // Create the Broker Subscription object
        String brokerSubscriptionId = SUBSCRIPTION_ID_PREFIX + UUID.randomUUID();
        BrokerSubscription brokerSubscription = BrokerSubscription.builder()
                .id(brokerSubscriptionId)
                .type(SUBSCRIPTION_TYPE)
                .entities(entities)
                .notification(BrokerSubscription.SubscriptionNotification.builder()
                        .subscriptionEndpoint(BrokerSubscription.SubscriptionNotification.SubscriptionEndpoint.builder()
                                .uri(brokerConfig.getNotificationEndpoint())
                                .accept(MediaType.APPLICATION_JSON_VALUE)
                                .receiverInfo(List.of(BrokerSubscription.SubscriptionNotification.SubscriptionEndpoint.RetrievalInfoContentType.builder()
                                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                                        .build()))
                                .build())
                        .build())
                .build();
        // Create the subscription and log the result
        return brokerListenerService.createSubscription(processId, brokerSubscription)
                .doFirst(() -> {
                    log.info("{} ProcessID: {} - Starting Broker Subscription creation", INIT, processId);
                    log.debug("ProcessID: {} - Broker Subscription: {}", processId, brokerSubscription);
                })
                .doOnSuccess(response -> log.info("{} ProcessID: {} - Broker Subscription created successfully", INIT, processId))
                .doOnError(e -> log.error("{} ProcessID: {} - Error creating Broker Subscription", INIT, processId, e));
    }

    private Mono<Void> setBlockchainSubscription(String processId) {
        // Check Subscription
        // Create the Blockchain Subscription object
        BlockchainSubscription blockchainSubscription = BlockchainSubscription.builder()
                .eventTypes(blockchainConfig.getRootObjects())
                .metadata(List.of(getEnvironmentMetadata(apiConfig.getCurrentEnvironment())))
                .notificationEndpoint(blockchainConfig.getNotificationEndpoint())
                .build();
        // Create the subscription
        return blockchainListenerService.createSubscription(processId, blockchainSubscription)
                .doFirst(() -> log.info("{} ProcessID: {} - Starting Blockchain Subscription creation", INIT, processId))
                .doOnSuccess(response -> log.info("{} ProcessID: {} - Blockchain Subscription created successfully", INIT, processId))
                .doOnError(e -> log.error("{} ProcessID: {} - Error creating Blockchain Subscription", INIT, processId, e));
    }

    private Mono<Void> getTrustedAccessNodesList(String processId) {
        return trustFrameworkConfig.initialize()
                .doFirst(() -> log.info("{} ProcessID: {} - Starting Trusted Access Nodes loading from repository list", INIT, processId))
                .doOnSuccess(response -> log.info("{} ProcessID: {} - Trusted Access Nodes loaded successfully in memory", INIT, processId))
                .doOnError(e -> log.error("{} ProcessID: {} - Error getting Trusted Access Nodes from repository list", INIT, processId, e));
    }

    // Never logs keys, tokens or the credential itself, only where and as whom the node runs. It runs inside the
    // first startup step, so it must never throw: a failure here would be taken as that step failing.
    private void logNodeConfiguration(String processId) {
        try {
            log.info("{} ProcessID: {} - profile={} externalDomain={} operator={} learCredentialMachineExpiration={}",
                    INIT, processId, apiConfig.getCurrentEnvironment(), apiConfig.getExternalDomain(),
                    apiConfig.getOrganizationId(),
                    learCredentialMachineConfig.getLearCredentialMachineExpiration()
                            .map(Instant::toString)
                            .orElse("unknown"));
        } catch (RuntimeException e) {
            log.warn("{} ProcessID: {} - Could not log the node configuration: {}", INIT, processId, e.getMessage());
        }
    }

    private Flux<Void> initializeDataSync(String processId) {
        AtomicReference<String> syncResult = new AtomicReference<>("not run");
        // Start data synchronization process. The Pub-Sub queues are enabled whether or not it succeeds:
        // new local changes and blockchain events do not depend on the initial sync, and leaving the
        // queues disabled would stop the node from publishing or receiving anything until a restart.
        // Data the sync could not fetch is picked up by the scheduled synchronization.
        return dataSyncWorkflow.startDataSyncWorkflow(processId)
                .doFirst(() -> log.info("{} ProcessID: {} - Starting initial Data Synchronization", INIT, processId))
                .doOnComplete(() -> {
                    syncResult.set("completed");
                    log.info("{} ProcessID: {} - Finished Initial Data Synchronization Workflow", INIT, processId);
                })
                // Logged once, by the caller's onErrorResume; here it is only kept for the READY line
                .doOnError(error -> syncResult.set("failed (" + error.getMessage() + ")"))
                .doOnTerminate(() -> {
                    log.info("{} ProcessID: {} - Authorizing queues for Pub-Sub Workflows", INIT, processId);
                    isQueueAuthorizedForEmit.set(true);
                    initializeQueueProcessing(processId);
                    log.info("{} ProcessID: {} - Queues have been authorized and enabled", INIT, processId);
                    log.info("{} ProcessID: {} - READY startup={} sync={} pendingPublish={} pendingSubscribe={}",
                            INIT, processId,
                            LogUtils.formatDuration(Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime())),
                            syncResult.get(), pendingPublishEventsQueue.pendingEvents(),
                            pendingSubscribeEventsQueue.pendingEvents());
                });
    }

    private void initializeQueueProcessing(String processId) {
        if (!isQueueProcessingAuthorized()) {
            log.debug("ProcessID: {} - Queue processing is currently paused", processId);
            return;
        }
        log.debug("ProcessID: {} - Starting queue processing...", processId);
        restartQueueProcessing(processId);
    }

    private boolean isQueueProcessingAuthorized() {
        return isQueueAuthorizedForEmit.get();
    }

    private void restartQueueProcessing(String processId) {
        log.debug("ProcessID: {} - Restarting queue processing", processId);
        resetActiveSubscriptions(processId);
        startBlockchainEventProcessing(processId);
        startBrokerEventProcessing(processId);
    }

    private void resetActiveSubscriptions(String processId) {
        log.debug("ProcessID: {} - Resetting active subscriptions", processId);
        disposeIfActive(publishQueueDisposable);
        disposeIfActive(subscribeQueueDisposable);
    }

    private void disposeIfActive(Disposable subscription) {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }

    private void startBlockchainEventProcessing(String processId) {
        publishQueueDisposable = publishWorkflow.startPublishWorkflow()
                .subscribe(
                        null,
                        error -> log.error("{} ProcessID: {} - Error occurred during Publish Workflow", INIT, processId, error),
                        () -> log.info("{} ProcessID: {} - Blockchain publish Workflow completed", INIT, processId)
                );
    }

    private void startBrokerEventProcessing(String processId) {
        subscribeQueueDisposable = subscribeWorkflow.startSubscribeWorkflow()
                .subscribe(
                        null,
                        error -> log.error("{} ProcessID: {} - Error occurred during Subscribe Workflow", INIT, processId, error),
                        () -> log.info("{} ProcessID: {} - Broker subscribe Workflow completed", INIT, processId)
                );
    }

    private void finishApplication(String step, Throwable error) {
        // A step that fails because the context is already shutting down (e.g. SIGTERM during startup)
        // must not force an exit with an error code on top of the ongoing shutdown.
        if (applicationContext instanceof ConfigurableApplicationContext configurableContext && !configurableContext.isActive()) {
            log.warn("{} {} failed while the application is shutting down: {}", INIT, step, error.getMessage());
            return;
        }
        Mono.fromRunnable(() -> {
            log.error("{} Error in {}: {}", INIT, step, error.getMessage(), error);
            // Uses the injected context, not DesmosApiApplication.getContext(): that static field is only set
            // after SpringApplication.run() returns, which is after ApplicationReadyEvent has been published.
            int exitCode = SpringApplication.exit(applicationContext, () -> 1);
            log.info("{} Application exiting with code {}", INIT, exitCode);
            exit(exitCode);
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    void exit(int exitCode) {
        System.exit(exitCode);
    }
}
