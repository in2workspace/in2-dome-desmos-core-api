package es.in2.desmos.application.workflows.impl;

import es.in2.desmos.application.workflows.PublishWorkflow;
import es.in2.desmos.domain.models.AuditRecordStatus;
import es.in2.desmos.domain.models.BrokerNotification;
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

import java.util.Objects;

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
                            return Mono.just((BrokerNotification) pendingPublishQueueEventStream.getEvent().get(0))
                                    .filter(Objects::nonNull)
                                    // Create an event from the BrokerNotification
                                    .flatMap(brokerNotification ->
                                            // Get the last AuditRecord stored for the same entityId; it is used to calculate the hashLink
                                            auditRecordService.fetchLatestProducerEntityHashLinkByEntityId(processId, brokerNotification.data().get(0).get("id").toString())
                                                    .switchIfEmpty(blockchainTxPayloadFactory.calculatePreviousHashIfEmpty(processId, brokerNotification.data().get(0)))
                                                    // Build the BlockchainTxPayload object
                                                    .flatMap(previousHashLink ->
                                                            blockchainTxPayloadFactory.buildBlockchainTxPayload(processId, brokerNotification.data().get(0), previousHashLink))
                                                    // Save a new Audit Record with status CREATED
                                                    .flatMap(blockchainTxPayload ->
                                                            auditRecordService.buildAndSaveAuditRecordFromBrokerNotification(processId, brokerNotification.data().get(0), AuditRecordStatus.CREATED, blockchainTxPayload)
                                                                    // Publish the data event to the Blockchain
                                                                    .then(blockchainPublisherService.publishDataToBlockchain(processId, blockchainTxPayload))
                                                                    .then(auditRecordService.buildAndSaveAuditRecordFromBrokerNotification(processId, brokerNotification.data().get(0), AuditRecordStatus.PUBLISHED, blockchainTxPayload))))
                                    .doOnSuccess(success ->
                                            log.info("ProcessID: {} - Publish Workflow completed successfully.", processId))
                                    .onErrorResume(error ->
                                            Mono.just(error)
                                                    .doOnNext(errorObject ->
                                                            log.error("ProcessID: {} - Error occurred while processing the Publish Workflow: {}", processId, errorObject.getMessage()))
                                                    .then(Mono.empty()));
                        }
                );
    }

}
