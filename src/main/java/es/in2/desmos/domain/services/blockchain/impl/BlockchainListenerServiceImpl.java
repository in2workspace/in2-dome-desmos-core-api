package es.in2.desmos.domain.services.blockchain.impl;

import es.in2.desmos.domain.exceptions.SubscriptionLookupException;
import es.in2.desmos.domain.exceptions.UnauthorizedDomeParticipantException;
import es.in2.desmos.domain.models.*;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.domain.services.api.QueueService;
import es.in2.desmos.domain.services.blockchain.BlockchainListenerService;
import es.in2.desmos.domain.services.blockchain.adapter.BlockchainAdapterService;
import es.in2.desmos.domain.services.blockchain.adapter.factory.BlockchainAdapterFactory;
import es.in2.desmos.infrastructure.configs.TrustFrameworkConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.HashSet;

import static es.in2.desmos.domain.utils.ApplicationUtils.extractEntityIdFromDataLocation;
import static es.in2.desmos.domain.utils.LogUtils.SUBSCRIBE;

@Slf4j
@Service
public class BlockchainListenerServiceImpl implements BlockchainListenerService {

    private final BlockchainAdapterService blockchainAdapterService;
    private final AuditRecordService auditRecordService;
    private final QueueService pendingSubscribeEventsQueue;
    private final TrustFrameworkConfig trustFrameworkConfig;

    public BlockchainListenerServiceImpl(BlockchainAdapterFactory blockchainAdapterFactory, AuditRecordService auditRecordService,
                                         QueueService pendingSubscribeEventsQueue,
                                         TrustFrameworkConfig trustFrameworkConfig) {
        this.blockchainAdapterService = blockchainAdapterFactory.getBlockchainAdapter();
        this.auditRecordService = auditRecordService;
        this.pendingSubscribeEventsQueue = pendingSubscribeEventsQueue;
        this.trustFrameworkConfig = trustFrameworkConfig;
    }

    @Override
    public Mono<Void> createSubscription(String processId, BlockchainSubscription blockchainSubscription) {
        return checkIfSubscriptionExists(processId, Mono.just(blockchainSubscription))
                .flatMap(x -> {
                    if (Boolean.TRUE.equals(x)) {
                        return Mono.empty();
                    } else {
                        return blockchainAdapterService.createSubscription(processId, blockchainSubscription);
                    }
                });
    }

    @Override
    public Mono<Void> processBlockchainNotification(String processId, BlockchainNotification blockchainNotification) {
        // Create and AuditRecord with status RECEIVED
        return checkIfParticipantExistsInTrustedList(processId, blockchainNotification)
                .then(auditRecordService.buildAndSaveAuditRecordFromBlockchainNotification(processId, blockchainNotification,
                                null, AuditRecordStatus.RECEIVED)
                        // Set priority for the pendingSubscribeEventsQueue event
                        .then(Mono.just(EventQueuePriority.MEDIUM))
                        // Enqueue DLTNotification to DataRetrievalQueue
                        .flatMap(eventQueuePriority -> {
                            log.debug("ProcessID: {} - Enqueuing Blockchain Notification to DataRetrievalQueue...", processId);
                            return pendingSubscribeEventsQueue.enqueueEvent(EventQueue.builder()
                                    .event(Collections.singletonList(blockchainNotification))
                                    .priority(eventQueuePriority)
                                    .build());
                        })
                );
    }

    private Mono<Boolean> checkIfSubscriptionExists(String processId, Mono<BlockchainSubscription> subscriptionToCreateMono) {
        return blockchainAdapterService
                .getSubscriptions(processId)
                .flatMap(existingSubscription ->
                        checkIfTwoSubscriptionsAreEquals(subscriptionToCreateMono, Mono.just(existingSubscription))
                                .filter(Boolean::booleanValue)
                                .hasElement()
                ).any(Boolean::booleanValue)
                .defaultIfEmpty(false)
                .timeout(SubscriptionLookupException.TIMEOUT)
                // Marks the read-only step so that the startup retries can tell it from creating the subscription
                .onErrorMap(error -> new SubscriptionLookupException("Error fetching subscriptions from DLT adapter", error));
    }

    private Mono<Boolean> checkIfTwoSubscriptionsAreEquals(
            Mono<BlockchainSubscription> sub1Mono,
            Mono<BlockchainSubscription> sub2Mono) {

        return Mono.zip(sub1Mono, sub2Mono)
                .map(tuple -> {
                    BlockchainSubscription sub1 = tuple.getT1();
                    BlockchainSubscription sub2 = tuple.getT2();

                    boolean areEventTypesEqual = new HashSet<>(sub1.eventTypes()).equals(new HashSet<>(sub2.eventTypes()));

                    boolean isNotificationEndpointEqual = sub1.notificationEndpoint().equals(sub2.notificationEndpoint());

                    return areEventTypesEqual && isNotificationEndpointEqual;
                });
    }

    private static String entityIdOf(String dataLocation) {
        try {
            return extractEntityIdFromDataLocation(dataLocation);
        } catch (IllegalArgumentException e) {
            return "(entity id not found in the data location)";
        }
    }

    private Mono<Void> checkIfParticipantExistsInTrustedList(String processId, BlockchainNotification blockchainNotification) {
        return Mono.fromRunnable(() -> {
            String currentDltAddress = blockchainNotification.ethereumAddress();
            log.debug("ProcessID: {} - Validating Dome Participant DLT Address from Blockchain notification: {}", processId, currentDltAddress);
            var dltAddresses = trustFrameworkConfig.getDltAddresses();

            boolean exists = dltAddresses
                    .stream()
                    .anyMatch(validAddress -> validAddress.equals(currentDltAddress.toLowerCase()));

            if (!exists) {
                // Only the trusted access nodes are expected to publish on the network, so an event from anyone else is
                // logged as an error with everything needed to find out who published it
                log.error("{} ProcessID: {} - Dome Participant not found: DLT Address {} from Blockchain notification is not in "
                                + "the trusted access nodes list, notification ignored - {} {} (event id {}, iss {}, dataLocation {})",
                        SUBSCRIBE, processId, currentDltAddress, blockchainNotification.eventType(),
                        entityIdOf(blockchainNotification.dataLocation()), blockchainNotification.id(),
                        blockchainNotification.publisherAddress(), blockchainNotification.dataLocation());
                throw new UnauthorizedDomeParticipantException("Dome Participant not found");
            } else {
                log.debug("ProcessID: {} - Dome Participant DLT Address is valid: {}", processId, currentDltAddress);
            }
        });
    }
}