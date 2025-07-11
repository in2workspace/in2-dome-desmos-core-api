package es.in2.desmos.application.workflows.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.application.workflows.SubscribeWorkflow;
import es.in2.desmos.domain.exceptions.JsonReadingException;
import es.in2.desmos.domain.models.AuditRecordStatus;
import es.in2.desmos.domain.models.BlockchainNotification;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.domain.services.api.QueueService;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import es.in2.desmos.domain.services.sync.services.DataSyncService;
import es.in2.desmos.domain.utils.ApplicationUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.NoSuchAlgorithmException;
import java.util.Objects;

import static es.in2.desmos.domain.utils.ApplicationConstants.ROOT_OBJECTS_LIST;

/*
 *  Workflow steps:
 *  1. Get the event from the SubscribeQueue.
 *  2. Retrieve the BrokerEntity from the external Broker.
 *  3. Verify the data integrity of the retrieved entity.
 *  4. Publish the retrieved entity to the local Broker.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscribeWorkflowImpl implements SubscribeWorkflow {

    private final QueueService pendingSubscribeEventsQueue;
    private final BrokerPublisherService brokerPublisherService;
    private final AuditRecordService auditRecordService;
    private final DataSyncService dataSyncService;
    private final ObjectMapper objectMapper;

    @Override
    public Flux<Void> startSubscribeWorkflow(String processId) {
        log.info("ProcessID: {} - Starting the Subscribe Workflow...", processId);
        // Get the event stream for the events that need to be synchronized to the local broker
        return pendingSubscribeEventsQueue.getEventStream()
                // Get the next event (BlockchainNotification) from the queue
                .flatMap(pendingSubscribeQueueEventStream ->
                        Mono.just((BlockchainNotification) pendingSubscribeQueueEventStream.getEvent().get(0))
                                // verify that the DLTNotification is not null
                                .filter(Objects::nonNull)
                                // retrieve the entity from the source broker
                                .flatMap(blockchainNotification ->
                                        dataSyncService.getEntityFromExternalSource(processId, blockchainNotification)
                                                // verify the data integrity of the retrieved entity
                                                .concatMap(retrievedBrokerEntity ->
                                                        {
                                                            String entityType = getEntityTypeFromJson(retrievedBrokerEntity);
                                                            String entityId = getEntityIdFromJson(retrievedBrokerEntity);

                                                            log.debug("ProcessID: {} - Receive object with id: {} has type: {}.", processId, entityId, entityType);

                                                            if (hasRootObjectType(entityType)) {
                                                                if (isCurrentRootObject(blockchainNotification, entityId)) {
                                                                    log.debug("ProcessID: {} - Retrieved entity with id: {} is root object", processId, entityId);

                                                                    // Verify the integrity and consistency of the retrieved entity
                                                                    return dataSyncService.verifyRetrievedEntityData(processId, blockchainNotification, retrievedBrokerEntity)
                                                                            // Build and save the audit record for RETRIEVED status
                                                                            .then(auditRecordService.buildAndSaveAuditRecordFromBlockchainNotification(processId, blockchainNotification, retrievedBrokerEntity, AuditRecordStatus.RETRIEVED))
                                                                            // Publish the retrieved entity to the local broker
                                                                            .then(brokerPublisherService.publishDataToBroker(processId, entityId, retrievedBrokerEntity))
                                                                            // Build and save the audit record for PUBLISHED status
                                                                            .then(auditRecordService.buildAndSaveAuditRecordFromBlockchainNotification(processId, blockchainNotification, retrievedBrokerEntity, AuditRecordStatus.PUBLISHED));
                                                                } else {
                                                                    log.debug("ProcessID: {} - Retrieved entity with id: {} has root object type but it isn't the current root object", processId, entityId);
                                                                    return auditRecordService.buildAndSaveAuditRecordForSubEntity(processId, entityId, entityType, retrievedBrokerEntity, AuditRecordStatus.RETRIEVED)
                                                                            .then(brokerPublisherService.publishDataToBroker(processId, entityId, retrievedBrokerEntity))
                                                                            .then(auditRecordService.buildAndSaveAuditRecordForSubEntity(processId, entityId, entityType, retrievedBrokerEntity, AuditRecordStatus.PUBLISHED));
                                                                }
                                                            } else {
                                                                String entityHash = calculateHash(retrievedBrokerEntity);
                                                                log.info("ProcessID: {} - AuditLog: RETRIEVED from Blockchain. Sub-object with id: {}, type: {} and hash: {}.", processId, entityId, entityType, entityHash);

                                                                return brokerPublisherService.publishDataToBroker(processId, entityId, retrievedBrokerEntity)
                                                                        .doOnSuccess(x -> log.info("ProcessID: {} - AuditLog: PUBLISHED to Broker. Sub-object with id: {}, type: {} and hash: {}.", processId, entityId, entityType, entityHash));
                                                            }
                                                        }
                                                )
                                                .collectList()
                                                .then()
                                                .doOnSuccess(success -> log.info("ProcessID: {} - Subscribe Workflow completed successfully.", processId))
                                                .onErrorResume(error ->
                                                        Mono.just(error)
                                                                .doOnNext(errorObject ->
                                                                        log.error("ProcessID: {} - Error occurred while processing the Subscribe Workflow: {}", processId, errorObject.getMessage()))
                                                                .then(Mono.empty()))
                                ));
    }

    private boolean isCurrentRootObject(BlockchainNotification blockchainNotification, String entityId) {
        String entityIdHash = "0x" + calculateHash(entityId);
        return entityIdHash.equals(blockchainNotification.entityId());
    }

    private boolean hasRootObjectType(String entityType) {
        return ROOT_OBJECTS_LIST.contains(entityType);
    }

    private String getEntityIdFromJson(String retrievedBrokerEntity) {
        try {
            JsonNode jsonEntity = objectMapper.readTree(retrievedBrokerEntity);
            return jsonEntity.get("id").asText();
        } catch (JsonProcessingException e) {
            throw new JsonReadingException(e.getMessage());
        }
    }

    private String getEntityTypeFromJson(String retrievedBrokerEntity) {
        try {
            JsonNode jsonEntity = objectMapper.readTree(retrievedBrokerEntity);
            return jsonEntity.get("type").asText();
        } catch (JsonProcessingException e) {
            throw new JsonReadingException(e.getMessage());
        }
    }

    private String calculateHash(String retrievedBrokerEntity) {
        try {
            return ApplicationUtils.calculateSHA256(retrievedBrokerEntity);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new JsonReadingException(e.getMessage());
        }
    }
}