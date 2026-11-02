package es.in2.desmos.application.workflows.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.application.workflows.SubscribeWorkflow;
import es.in2.desmos.domain.exceptions.JsonReadingException;
import es.in2.desmos.domain.models.AuditRecordStatus;
import es.in2.desmos.domain.models.BlockchainNotification;
import es.in2.desmos.domain.models.BrokerWriteAction;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static es.in2.desmos.domain.utils.ApplicationConstants.ROOT_OBJECTS_LIST;
import static es.in2.desmos.domain.utils.LogUtils.SUBSCRIBE;

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

    private static final int LOGGED_HASH_LENGTH = 12;

    // An entity written to the local broker while processing a blockchain event, only used for the [subscribe] line
    private record StoredEntity(String type, String id, BrokerWriteAction action, boolean notifiedEntity, String hash) {
        @Override
        public String toString() {
            return type + " " + id + " (" + action.name().toLowerCase() + (hash != null ? ", hash=" + hash : "") + ")";
        }
    }

    private final QueueService pendingSubscribeEventsQueue;
    private final BrokerPublisherService brokerPublisherService;
    private final AuditRecordService auditRecordService;
    private final DataSyncService dataSyncService;
    private final ObjectMapper objectMapper;

    @Override
    public Flux<Void> startSubscribeWorkflow() {
        // Get the event stream for the events that need to be synchronized to the local broker
        return pendingSubscribeEventsQueue.getEventStream()
                .doFirst(() -> log.info("Starting the Subscribe Workflow"))
                // Get the next event (BlockchainNotification) from the queue
                .flatMap(pendingSubscribeQueueEventStream -> {
                    String processId = ApplicationUtils.generateProcessId();
                    Instant startTime = Instant.now();
                    return Mono.just((BlockchainNotification) pendingSubscribeQueueEventStream.getEvent().get(0))
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
                                                                        .then(brokerPublisherService.upsertDataInBroker(processId, entityId, retrievedBrokerEntity))
                                                                        // Build and save the audit record for PUBLISHED status
                                                                        .flatMap(action -> auditRecordService.buildAndSaveAuditRecordFromBlockchainNotification(processId, blockchainNotification, retrievedBrokerEntity, AuditRecordStatus.PUBLISHED)
                                                                                .thenReturn(new StoredEntity(entityType, entityId, action, true, null)));
                                                            } else {
                                                                log.debug("ProcessID: {} - Retrieved entity with id: {} has root object type but it isn't the current root object", processId, entityId);
                                                                return auditRecordService.buildAndSaveAuditRecordForSubEntity(processId, entityId, entityType, retrievedBrokerEntity, AuditRecordStatus.RETRIEVED)
                                                                        .then(brokerPublisherService.upsertDataInBroker(processId, entityId, retrievedBrokerEntity))
                                                                        .flatMap(action -> auditRecordService.buildAndSaveAuditRecordForSubEntity(processId, entityId, entityType, retrievedBrokerEntity, AuditRecordStatus.PUBLISHED)
                                                                                .thenReturn(new StoredEntity(entityType, entityId, action, false, null)));
                                                            }
                                                        } else {
                                                            // Not a replicated type, so no audit record: its hash goes to the [subscribe] line
                                                            String entityHash = calculateHash(retrievedBrokerEntity);
                                                            log.debug("ProcessID: {} - AuditLog: RETRIEVED from Blockchain. Sub-object with id: {}, type: {} and hash: {}.", processId, entityId, entityType, entityHash);

                                                            return brokerPublisherService.upsertDataInBroker(processId, entityId, retrievedBrokerEntity)
                                                                    .doOnSuccess(x -> log.debug("ProcessID: {} - AuditLog: PUBLISHED to Broker. Sub-object with id: {}, type: {} and hash: {}.", processId, entityId, entityType, entityHash))
                                                                    .map(action -> new StoredEntity(entityType, entityId, action, false, shortHash(entityHash)));
                                                        }
                                                    }
                                            )
                                            .collectList()
                                            .doOnNext(storedEntities -> logStored(processId, blockchainNotification, storedEntities,
                                                    Duration.between(startTime, Instant.now()).toMillis()))
                                            .then()
                                            .onErrorResume(error ->
                                                    Mono.just(error)
                                                            .doOnNext(errorObject ->
                                                                    log.error("{} ProcessID: {} - {} event={} publisher={} dataLocation={} -> error after {} ms: {}",
                                                                            SUBSCRIBE, processId, blockchainNotification.eventType(), blockchainNotification.id(),
                                                                            blockchainNotification.ethereumAddress(), blockchainNotification.dataLocation(),
                                                                            Duration.between(startTime, Instant.now()).toMillis(), errorObject.getMessage()))
                                                            .then(Mono.empty()))
                            );
                });
    }

    // One line per blockchain event with everything the per-step lines used to say: the notified entity and whether
    // it was created or updated, where it came from and who published it, and the subentities written with it
    private static void logStored(String processId, BlockchainNotification blockchainNotification,
                                  List<StoredEntity> storedEntities, long durationMillis) {
        String notified = storedEntities.stream()
                .filter(StoredEntity::notifiedEntity)
                .map(StoredEntity::toString)
                .collect(Collectors.joining(", "));
        List<StoredEntity> subentities = storedEntities.stream().filter(stored -> !stored.notifiedEntity()).toList();
        log.info("{} ProcessID: {} - {} -> stored{} in {} ms (event={} publisher={} dataLocation={})",
                SUBSCRIBE, processId, notified.isEmpty() ? blockchainNotification.eventType() + " (not returned by the origin)" : notified,
                subentities.isEmpty() ? "" : " with " + subentities.size() + " subentities " + subentities,
                durationMillis, blockchainNotification.id(), blockchainNotification.ethereumAddress(),
                blockchainNotification.dataLocation());
    }

    private static String shortHash(String hash) {
        return hash.length() > LOGGED_HASH_LENGTH ? hash.substring(0, LOGGED_HASH_LENGTH) : hash;
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