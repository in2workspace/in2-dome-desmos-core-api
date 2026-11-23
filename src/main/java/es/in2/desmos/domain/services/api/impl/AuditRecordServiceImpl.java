package es.in2.desmos.domain.services.api.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.exceptions.JsonReadingException;
import es.in2.desmos.domain.models.*;
import es.in2.desmos.domain.repositories.AuditRecordRepository;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import es.in2.desmos.domain.utils.ApplicationUtils;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.infrastructure.configs.EndpointsConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static es.in2.desmos.domain.utils.ApplicationConstants.HASHLINK_PREFIX;
import static es.in2.desmos.domain.utils.ApplicationUtils.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditRecordServiceImpl implements AuditRecordService {

    private final ObjectMapper objectMapper;
    private final AuditRecordRepository auditRecordRepository;
    private final BrokerPublisherService brokerPublisherService;
    private final ApiConfig apiConfig;
    private final EndpointsConfig endpointsConfig;
    private final AuditChainWriter auditChainWriter;


    /**
     * Appends an AuditRecord with trader PRODUCER from the data received in the broker notification: RECEIVED if the
     * BlockchainTxPayload is null, otherwise CREATED or PUBLISHED.
     */
    @Override
    public Mono<Void> buildAndSaveAuditRecordFromBrokerNotification(String processId, Map<String, Object> dataMap,
                                                                    AuditRecordStatus status, BlockchainTxPayload blockchainTxPayload) {
        return Mono.defer(() -> {
            String entityId = dataMap.get("id").toString();
            log.debug("ProcessID: {} - Building and saving audit record from broker notification for entityId {}", processId, entityId);
            String entityHash;
            String entityHashLink;
            String dataLocation;
            if (blockchainTxPayload == null) {
                // status cases: RECEIVED
                entityHash = "";
                entityHashLink = "";
                dataLocation = "";
            } else {
                // status cases: CREATED, PUBLISHED
                try {
                    entityHash = calculateSHA256(objectMapper.writeValueAsString(dataMap));
                } catch (JsonProcessingException | NoSuchAlgorithmException e) {
                    log.warn("ProcessID: {} - Error building and saving audit record: {}", processId, e.getMessage());
                    return Mono.error(e);
                }
                dataLocation = blockchainTxPayload.dataLocation();
                entityHashLink = extractHashLinkFromDataLocation(dataLocation);
            }
            AuditRecord draft = AuditRecord.builder()
                    .id(UUID.randomUUID())
                    .processId(processId)
                    .entityId(entityId)
                    .entityType(dataMap.get("type").toString())
                    .entityHash(entityHash)
                    .entityHashLink(entityHashLink)
                    .dataLocation(dataLocation)
                    .status(status)
                    .trader(AuditRecordTrader.PRODUCER)
                    .newTransaction(true)
                    .build();
            return auditChainWriter.append(processId, List.of(draft), "broker notification");
        });
    }

    /**
     * Appends an AuditRecord with trader CONSUMER from the blockchain notification and the entity retrieved from the
     * external broker: RECEIVED if the retrieved entity is null or blank, otherwise RETRIEVED or PUBLISHED.
     */
    @Override
    public Mono<Void> buildAndSaveAuditRecordFromBlockchainNotification(String processId, BlockchainNotification blockchainNotification,
                                                                        String retrievedBrokerEntity, AuditRecordStatus status) {
        return Mono.defer(() -> {
            String entityHash;
            String entityHashLink;
            if (retrievedBrokerEntity == null || retrievedBrokerEntity.isBlank()) {
                // status cases: RECEIVED
                entityHash = "";
                entityHashLink = "";
            } else {
                // status cases: RETRIEVED, PUBLISHED
                // We do not need to sort the fields of the retrievedBrokerEntity
                // because these have already been sorted in the
                // SubscribeWorkflowImpl.sortAttributesAlphabetically()
                try {
                    entityHash = calculateSHA256(retrievedBrokerEntity);
                } catch (JsonProcessingException | NoSuchAlgorithmException e) {
                    return Mono.error(e);
                }
                entityHashLink = extractHashLinkFromDataLocation(blockchainNotification.dataLocation());
            }
            AuditRecord draft = AuditRecord.builder()
                    .id(UUID.randomUUID())
                    .processId(processId)
                    .entityId(extractEntityIdFromDataLocation(blockchainNotification.dataLocation()))
                    .entityType(blockchainNotification.eventType())
                    .entityHash(entityHash)
                    .entityHashLink(entityHashLink)
                    .dataLocation(blockchainNotification.dataLocation())
                    .status(status)
                    .trader(AuditRecordTrader.CONSUMER)
                    .newTransaction(true)
                    .build();
            return auditChainWriter.append(processId, List.of(draft), "blockchain notification");
        });
    }

    /**
     * Appends one RETRIEVED/CONSUMER audit record per entity whose body hash differs from the one of its last RETRIEVED
     * or DELETED record (or that has none), in one write. The echo check of a broker notification compares against
     * that record, so an entity already recorded with the same hash needs no new one. The read and the write are not
     * atomic: two concurrent calls may both write the same hash, which is harmless.
     */
    @Override
    public Mono<Integer> saveImplicitConsumerRetrieved(String processId, List<ImplicitRetrievedEntity> entities) {
        return Mono.defer(() -> {
            if (entities.isEmpty()) {
                return Mono.just(0);
            }
            List<AuditRecord> candidates = entities.stream()
                    .map(entity -> implicitRetrievedDraft(processId, entity, calculateHash(entity.body())))
                    .toList();
            String[] entityIds = candidates.stream().map(AuditRecord::getEntityId).distinct().toArray(String[]::new);
            return auditRecordRepository.findMostRecentRetrievedOrDeletedByEntityIds(entityIds)
                    .collectMap(AuditRecord::getEntityId, AuditRecord::getEntityHash)
                    .flatMap(lastHashById -> {
                        List<AuditRecord> drafts = candidates.stream()
                                .filter(draft -> !draft.getEntityHash().equals(lastHashById.get(draft.getEntityId())))
                                .toList();
                        if (drafts.isEmpty()) {
                            return Mono.just(0);
                        }
                        return auditChainWriter.append(processId, drafts, "implicit subentity")
                                .thenReturn(drafts.size());
                    });
        });
    }

    // No hashlink of its own, as for a first record: the entity has no blockchain event, and its hashlink is never read
    private static AuditRecord implicitRetrievedDraft(String processId, ImplicitRetrievedEntity entity, String entityHash) {
        return AuditRecord.builder()
                .id(UUID.randomUUID())
                .processId(processId)
                .entityId(entity.id())
                .entityType(entity.type())
                .entityHash(entityHash)
                .entityHashLink(entityHash)
                .dataLocation("")
                .status(AuditRecordStatus.RETRIEVED)
                .trader(AuditRecordTrader.CONSUMER)
                .newTransaction(true)
                .build();
    }

    /**
     * Appends one audit record with trader CONSUMER per entity of the P2P synchronization (RECEIVED, RETRIEVED or
     * PUBLISHED), in batches and in the given order. The entities of a RECEIVED record carry empty hashes.
     */
    @Override
    public Mono<Void> buildAndSaveAuditRecordsFromDataSync(String processId,
                                                           List<MVAuditServiceEntity4DataNegotiation> entities,
                                                           AuditRecordStatus status) {
        List<AuditRecord> drafts = entities.stream()
                .map(entity -> AuditRecord.builder()
                        .id(UUID.randomUUID())
                        .processId(processId)
                        .entityId(entity.id())
                        .entityType(entity.type())
                        .entityHash(entity.hash())
                        .entityHashLink(entity.hashlink())
                        .dataLocation("")
                        .status(status)
                        .trader(AuditRecordTrader.CONSUMER)
                        .newTransaction(true)
                        .build())
                .toList();
        return auditChainWriter.append(processId, drafts, "p2p sync");
    }

    private String getDataLocationForProducedEntity(String issuer, MVAuditServiceEntity4DataNegotiation mvAuditServiceEntity4DataNegotiation) {
        return issuer +
                endpointsConfig.getEntitiesEndpoint() +
                "/" +
                mvAuditServiceEntity4DataNegotiation.id() +
                HASHLINK_PREFIX
                + mvAuditServiceEntity4DataNegotiation.hashlink();
    }

    /**
     * Retrieves the most recent audit record for the specified entity that is either published or deleted.
     *
     * @param processId The unique identifier of the process requesting the audit record.
     * @param entityId  The unique identifier of the entity for which to find the audit record.
     * @return A Mono emitting the latest published or deleted audit record for the given entity, if available.
     */
    @Override
    public Mono<AuditRecord> findMostRecentRetrievedOrDeletedByEntityId(String processId, String entityId) {
        log.debug("ProcessID: {} - Fetching latest audit record for entity ID: {}", processId, entityId);
        return auditRecordRepository.findMostRecentRetrievedOrDeletedByEntityId(entityId);
    }

    @Override
    public Mono<AuditRecord> getLastPublishedAuditRecordForProducerByEntityId(String processId, String entityId) {
        log.debug("ProcessID: {} - Getting last audit record by entity id and producer: {}", processId, entityId);
        return auditRecordRepository.findLatestPublishedAuditRecordForProducerByEntityId(entityId);
    }

    @Override
    public Mono<String> fetchLatestProducerEntityHashLinkByEntityId(String processId, String entityId) {
        return getLastPublishedAuditRecordForProducerByEntityId(processId, entityId)
                .flatMap(auditRecord -> auditRecord != null
                        ? Mono.just(auditRecord.getEntityHashLink())
                        : Mono.error(new NoSuchElementException()));
    }

    @Override
    public Mono<AuditRecord> findLatestConsumerPublishedAuditRecordByEntityId(String processId, String entityId) {
        log.debug("ProcessID: {} - Fetching all audit records...", processId);
        return auditRecordRepository.findLastPublishedConsumerAuditRecordByEntityId(entityId);
    }

    @Override
    public Mono<AuditRecord> findLatestConsumerPublishedAuditRecord(String processId) {
        log.debug("ProcessID: {} - Fetching all audit records...", processId);
        return auditRecordRepository.findLastPublishedConsumerAuditRecord();
    }

    /**
     * Returns the negotiation data of the given local entities and audits as PUBLISHED those without an audit record
     * or whose hash in the broker has changed. An entity without a PUBLISHED record but with a CONSUMER RETRIEVED one
     * came from another node: it is announced with its broker hash and not audited as a PRODUCER entity. The broker hashes are read in parallel; the new audit records are then
     * appended in one batch write, after every hash has been read.
     */
    @Override
    public Flux<MVAuditServiceEntity4DataNegotiation> findCreateOrUpdateAuditRecordsByEntityIds(
            String processId,
            String entityType,
            Flux<String> entityIdsFlux) {

        return entityIdsFlux
                .collectList()
                .flatMapMany(entityIds -> {
                    if (entityIds.isEmpty()) {
                        return Flux.empty();
                    }
                    String[] entityIdsArray = entityIds.toArray(new String[0]);
                    return Mono.zip(
                                    auditRecordRepository.findMostRecentPublishedAuditRecordsByEntityIds(entityIdsArray)
                                            .collectMap(AuditRecord::getEntityId),
                                    auditRecordRepository.findMostRecentRetrievedOrDeletedByEntityIds(entityIdsArray)
                                            .filter(auditRecord -> auditRecord.getTrader() == AuditRecordTrader.CONSUMER)
                                            .map(AuditRecord::getEntityId)
                                            .collect(Collectors.toSet()))
                            .flatMapMany(tuple -> {
                                Map<String, AuditRecord> auditRecordMap = tuple.getT1();
                                Set<String> consumerRetrievedIds = tuple.getT2();
                                log.debug("ProcessID: {} - auditRecordMap keys: {}", processId, auditRecordMap.keySet());
                                return Flux.fromIterable(entityIds)
                                        .flatMap(id -> getEntityHash(processId, id)
                                                .flatMap(entityHash -> {
                                                    AuditRecord auditRecord = auditRecordMap.get(id);
                                                    if (auditRecord != null) {
                                                        return findOrUpdateAuditRecord(processId, entityHash, auditRecord);
                                                    }
                                                    if (consumerRetrievedIds.contains(id)) {
                                                        // Retrieved from another node (a subentity) and never published
                                                        // here: announced with its broker hash, without becoming a
                                                        // PRODUCER entity of this node
                                                        log.debug("ProcessID: {} - Only a CONSUMER RETRIEVED auditRecord found for entityId: {}", processId, id);
                                                        return Mono.just(new SyncAuditResult(
                                                                new MVAuditServiceEntity4DataNegotiation(id, entityType, entityHash, entityHash),
                                                                null));
                                                    }
                                                    log.debug("ProcessID: {} - NO auditRecord found for entityId: {}", processId, id);
                                                    return Mono.just(draftFromUnregisteredOrOutdatedEntity(
                                                            processId,
                                                            new MVAuditServiceEntity4DataNegotiation(id, entityType, entityHash, entityHash),
                                                            AuditRecordTrader.PRODUCER,
                                                            null));
                                                }))
                                        .collectList()
                                        .flatMapMany(results -> {
                                            List<AuditRecord> drafts = results.stream()
                                                    .map(SyncAuditResult::draft)
                                                    .filter(Objects::nonNull)
                                                    .toList();
                                            return auditChainWriter.append(processId, drafts, "unregistered or outdated entity")
                                                    .thenMany(Flux.fromIterable(results).map(SyncAuditResult::entity));
                                        });
                            });
                });
    }

    // The negotiation data of an entity and, if it has to be audited, the draft of its new audit record
    private record SyncAuditResult(MVAuditServiceEntity4DataNegotiation entity, AuditRecord draft) {
    }

    private Mono<SyncAuditResult> findOrUpdateAuditRecord(String processId, String entityHash, AuditRecord auditRecord) {
        if (entityHash.equals(auditRecord.getEntityHash())) {
            return Mono.just(new SyncAuditResult(new MVAuditServiceEntity4DataNegotiation(
                    auditRecord.getEntityId(),
                    auditRecord.getEntityType(),
                    auditRecord.getEntityHash(),
                    auditRecord.getEntityHashLink()
            ), null));
        }
        return Mono.fromCallable(() -> ApplicationUtils.calculateHashLink(auditRecord.getEntityHashLink(), entityHash))
                .map(calculatedHashLink -> {
                    // The new record keeps the trader of the previous one; a CONSUMER one has no data location
                    String newAuditRecordDataLocation =
                            auditRecord.getTrader().equals(AuditRecordTrader.CONSUMER) ? "" : auditRecord.getDataLocation();
                    return draftFromUnregisteredOrOutdatedEntity(
                            processId,
                            new MVAuditServiceEntity4DataNegotiation(
                                    auditRecord.getEntityId(),
                                    auditRecord.getEntityType(),
                                    entityHash,
                                    calculatedHashLink
                            ),
                            auditRecord.getTrader(),
                            newAuditRecordDataLocation);
                });
    }

    private SyncAuditResult draftFromUnregisteredOrOutdatedEntity(String processId, MVAuditServiceEntity4DataNegotiation mvAuditServiceEntity4DataNegotiation, AuditRecordTrader trader, String dataLocation) {
        String newDataLocation = Objects.requireNonNullElseGet(
                dataLocation,
                () -> getDataLocationForProducedEntity(apiConfig.getExternalDomain(), mvAuditServiceEntity4DataNegotiation));
        AuditRecord draft = AuditRecord.builder()
                .id(UUID.randomUUID())
                .processId(processId)
                .entityId(mvAuditServiceEntity4DataNegotiation.id())
                .entityType(mvAuditServiceEntity4DataNegotiation.type())
                .entityHash(mvAuditServiceEntity4DataNegotiation.hash())
                .entityHashLink(mvAuditServiceEntity4DataNegotiation.hashlink())
                .dataLocation(newDataLocation)
                .status(AuditRecordStatus.PUBLISHED)
                .trader(trader)
                .newTransaction(true)
                .build();
        return new SyncAuditResult(mvAuditServiceEntity4DataNegotiation, draft);
    }

    private Mono<String> getEntityHash(String processId, String entityId) {
        long startedAt = System.currentTimeMillis();
        return brokerPublisherService.getEntityById(processId, entityId)
                .flatMap(entity -> {
                    try {
                        return Mono.just(ApplicationUtils.calculateSHA256(entity));
                    } catch (NoSuchAlgorithmException | JsonProcessingException e) {
                        return Mono.error(e);
                    }
                })
                .doOnSuccess(result ->
                        log.debug("ProcessID: {} - getEntityHash completed in {} ms", processId, System.currentTimeMillis() - startedAt));
    }

    private String calculateHash(String retrievedBrokerEntity) {
        try {
            return ApplicationUtils.calculateSHA256(retrievedBrokerEntity);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new JsonReadingException(e.getMessage());
        }
    }

}