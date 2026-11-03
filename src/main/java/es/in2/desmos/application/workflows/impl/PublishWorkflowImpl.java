package es.in2.desmos.application.workflows.impl;

import es.in2.desmos.application.workflows.PublishWorkflow;
import es.in2.desmos.domain.exceptions.DltPublicationException;
import es.in2.desmos.domain.models.AuditRecordStatus;
import es.in2.desmos.domain.models.BrokerNotification;
import es.in2.desmos.domain.models.EventQueue;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.domain.services.api.QueueService;
import es.in2.desmos.domain.services.blockchain.BlockchainPublisherService;
import es.in2.desmos.domain.utils.ApplicationUtils;
import es.in2.desmos.domain.utils.BlockchainTxPayloadFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import static es.in2.desmos.domain.utils.LogUtils.PUBLISH;

@Slf4j
@Service
@RequiredArgsConstructor
public class PublishWorkflowImpl implements PublishWorkflow {

    /*
     *  Workflow steps:
     *  1. Get the event from the PublisherQueue.
     *  2. Build the BlockchainTxPayload.
     *  3. Publish the BlockchainTxPayload to the Blockchain.
     */

    private final QueueService pendingPublishEventsQueue;
    private final AuditRecordService auditRecordService;
    private final BlockchainTxPayloadFactory blockchainTxPayloadFactory;
    private final BlockchainPublisherService blockchainPublisherService;

    @Override
    public Flux<Void> startPublishWorkflow() {
        // Get the event stream from the data publication queue
        return pendingPublishEventsQueue.getEventStream()
                .doFirst(() -> log.info("Starting the Publish Workflow"))
                // Get the first event from the event stream,
                // parse it as a BrokerNotification and filter out null values
                .flatMap(pendingPublishQueueEventStream -> {
                            String processId = ApplicationUtils.generateProcessId();
                            Instant startTime = Instant.now();
                            return Mono.just((BrokerNotification) pendingPublishQueueEventStream.getEvent().get(0))
                                    .filter(Objects::nonNull)
                                    // Create an event from the BrokerNotification
                                    .flatMap(brokerNotification ->
                                            // Get the last AuditRecord stored for the same entityId; it is used to calculate the hashLink
                                    {
                                        log.debug("ProcessID: {} - Starting publishing workflow for entityId: {}", processId, brokerNotification.data().get(0).get("id").toString());
                                        return auditRecordService.fetchLatestProducerEntityHashLinkByEntityId(processId, brokerNotification.data().get(0).get("id").toString())
                                                .switchIfEmpty(blockchainTxPayloadFactory.calculatePreviousHashIfEmpty(processId, brokerNotification.data().get(0)))
                                                // Build the BlockchainTxPayload object
                                                .flatMap(previousHashLink ->
                                                        blockchainTxPayloadFactory.buildBlockchainTxPayload(processId, brokerNotification.data().get(0), previousHashLink))
                                                // Save a new Audit Record with status CREATED
                                                .flatMap(blockchainTxPayload -> {
                                                    log.debug("ProcessID: {} - Creating blockchainTxPayload for entityId: {}", processId, brokerNotification.data().get(0).get("id").toString());
                                                    return auditRecordService.buildAndSaveAuditRecordFromBrokerNotification(processId, brokerNotification.data().get(0), AuditRecordStatus.CREATED, blockchainTxPayload)
                                                            // Publish the data event to the Blockchain
                                                            .then(blockchainPublisherService.publishDataToBlockchain(processId, blockchainTxPayload))
                                                            .then(auditRecordService.buildAndSaveAuditRecordFromBrokerNotification(processId, brokerNotification.data().get(0), AuditRecordStatus.PUBLISHED, blockchainTxPayload));
                                                });
                                    })
                                    // The "[publish] <type> <id> -> tx=..." line is written by the DLT adapter client
                                    .doOnSuccess(success ->
                                            log.debug("ProcessID: {} - Publish Workflow completed successfully in {} ms.",
                                                    processId, Duration.between(startTime, Instant.now()).toMillis()))
                                    .onErrorResume(error ->
                                            Mono.just(error)
                                                    .doOnNext(errorObject -> {
                                                        Map<String, Object> entity = firstEntityOf(pendingPublishQueueEventStream);
                                                        if (errorObject instanceof DltPublicationException) {
                                                            // Already logged by the DLT adapter client with its details
                                                            log.debug("{} ProcessID: {} - {} {} -> not published, PUBLISHED audit record not saved",
                                                                    PUBLISH, processId, entity.get("type"), entity.get("id"));
                                                        } else {
                                                            log.error("{} ProcessID: {} - {} {} -> error after {} ms: {}",
                                                                    PUBLISH, processId, entity.get("type"), entity.get("id"),
                                                                    Duration.between(startTime, Instant.now()).toMillis(), errorObject.getMessage());
                                                        }
                                                    })
                                                    .then(Mono.empty()));
                        }
                );
    }

    private static Map<String, Object> firstEntityOf(EventQueue eventQueue) {
        try {
            return ((BrokerNotification) eventQueue.getEvent().get(0)).data().get(0);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

}
