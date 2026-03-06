package es.in2.desmos.application.runners;

import es.in2.desmos.DesmosApiApplication;
import es.in2.desmos.application.workflows.DataSyncWorkflow;
import es.in2.desmos.application.workflows.PublishWorkflow;
import es.in2.desmos.application.workflows.SubscribeWorkflow;
import es.in2.desmos.domain.exceptions.RequestErrorException;
import es.in2.desmos.domain.models.BlockchainSubscription;
import es.in2.desmos.domain.models.BrokerSubscription;
import es.in2.desmos.domain.services.blockchain.BlockchainListenerService;
import es.in2.desmos.domain.services.broker.BrokerListenerService;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.infrastructure.configs.BlockchainConfig;
import es.in2.desmos.infrastructure.configs.BrokerConfig;
import es.in2.desmos.infrastructure.configs.TrustFrameworkConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static es.in2.desmos.domain.utils.ApplicationConstants.SUBSCRIPTION_ID_PREFIX;
import static es.in2.desmos.domain.utils.ApplicationConstants.SUBSCRIPTION_TYPE;
import static es.in2.desmos.domain.utils.ApplicationUtils.getEnvironmentMetadata;

@Slf4j
@Configuration
@RequiredArgsConstructor
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
    private final AtomicBoolean isQueueAuthorizedForEmit = new AtomicBoolean(false);
    private final String getCurrentEnvironment;
    private Disposable publishQueueDisposable;
    private Disposable subscribeQueueDisposable;

    @EventListener(ApplicationReadyEvent.class)
    public Mono<Void> onApplicationReady() {
        String processId = UUID.randomUUID().toString();
        return setBrokerSubscription(processId)
                .doFirst(() -> log.info("ProcessID: {} - Starting configuration initialization", processId))
                .doOnError(error -> finishApplication("Broker Subscription", error))
                .then(setBlockchainSubscription(processId)
                        .doOnError(error -> finishApplication("Blockchain Subscription", error)))
                .then(getTrustedAccessNodesList(processId)
                        .doOnError(error -> finishApplication("Access Node Trusted List Getting", error)))
                .thenMany(initializeDataSync(processId)
                        .onErrorResume(error -> {
                            log.error("ProcessID: {} - Error initializing Data Sync: {}", processId, error.getMessage(), error);
                            return Flux.empty();
                        }))
                .then();
    }

    @Retryable(retryFor = RequestErrorException.class, maxAttempts = 4, backoff = @Backoff(delay = 2000))
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
                    log.info("ProcessID: {} - Starting Broker Subscription creation", processId);
                    log.debug("ProcessID: {} - Broker Subscription: {}", processId, brokerSubscription);
                })
                .doOnSuccess(response -> log.info("ProcessID: {} - Broker Subscription created successfully", processId))
                .doOnError(e -> log.error("ProcessID: {} - Error creating Broker Subscription", processId, e));
    }

    @Retryable(retryFor = RequestErrorException.class, maxAttempts = 4, backoff = @Backoff(delay = 2000))
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
                .doFirst(() -> log.info("ProcessID: {} - Starting Blockchain Subscription creation", processId))
                .doOnSuccess(response -> log.info("ProcessID: {} - Blockchain Subscription created successfully", processId))
                .doOnError(e -> log.error("ProcessID: {} - Error creating Blockchain Subscription", processId, e));
    }

    @Retryable(retryFor = RequestErrorException.class, maxAttempts = 4, backoff = @Backoff(delay = 2000))
    private Mono<Void> getTrustedAccessNodesList(String processId) {
        return trustFrameworkConfig.initialize()
                .doFirst(() -> log.info("ProcessID: {} - Starting Trusted Access Nodes loading from repository list", processId))
                .doOnSuccess(response -> log.info("ProcessID: {} - Trusted Access Nodes loaded successfully in memory", processId))
                .doOnError(e -> log.error("ProcessID: {} - Error getting Trusted Access Nodes from repository list", processId, e));
    }

    private Flux<Void> initializeDataSync(String processId) {
        // Start data synchronization process
        return dataSyncWorkflow.startDataSyncWorkflow(processId)
                .doFirst(() -> log.info("ProcessID: {} - Starting initial Data Synchronization", processId))
                .doOnComplete(() -> {
                    log.info("ProcessID: {} - Finished Initial Data Synchronization Workflow", processId);
                    log.info("ProcessID: {} - Authorizing queues for Pub-Sub Workflows", processId);
                    isQueueAuthorizedForEmit.set(true);
                })
                .doOnTerminate(() -> {
                    initializeQueueProcessing(processId);
                    log.info("ProcessID: {} - Queues have been authorized and enabled", processId);
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
                        error -> log.error("ProcessID: {} - Error occurred during Publish Workflow", processId, error),
                        () -> log.info("ProcessID: {} - Blockchain publish Workflow completed", processId)
                );
    }

    private void startBrokerEventProcessing(String processId) {
        subscribeQueueDisposable = subscribeWorkflow.startSubscribeWorkflow()
                .subscribe(
                        null,
                        error -> log.error("ProcessID: {} - Error occurred during Subscribe Workflow", processId, error),
                        () -> log.info("ProcessID: {} - Broker subscribe Workflow completed", processId)
                );
    }

    private void finishApplication(String step, Throwable error) {
        Mono.fromRunnable(() -> {
            log.error("Error in {}: {}", step, error.getMessage(), error);
            int exitCode = SpringApplication.exit(DesmosApiApplication.getContext(), () -> 0);
            log.info("Application exiting with code {}", exitCode);
            System.exit(exitCode);
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }
}
