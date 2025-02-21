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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

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

    /**
     * Create a new AuditRecord with status CREATED or PUBLISHED and trader CONSUMER
     * from the data received in the broker notification.
     * If the BlockchainTxPayload is null, the status will be RECEIVED.
     */
    @Override
    public Mono<Void> buildAndSaveAuditRecordFromBrokerNotification(String processId, Map<String, Object> dataMap,
                                                                    AuditRecordStatus status, BlockchainTxPayload blockchainTxPayload) {
        log.info("ProcessID: {} - Building and saving audit record from broker notification...", processId);
        // Extract the entity ID from the data location
        String entityId = dataMap.get("id").toString();
        // Get the most recent audit record for the entityId and get the most recent audit record overall
        return fetchMostRecentAuditRecord()
                .flatMap(lastAuditRecordRegistered -> {
                    // Create the new audit record
                    AuditRecord auditRecord = AuditRecord.builder()
                            .id(UUID.randomUUID())
                            .processId(processId)
                            .createdAt(Timestamp.from(Instant.now()))
                            .entityId(entityId)
                            .entityType(dataMap.get("type").toString())
                            .status(status)
                            .trader(AuditRecordTrader.PRODUCER)
                            .hash("")
                            .hashLink("")
                            .newTransaction(true)
                            .build();
                    try {
                        String dataLocation;
                        if (blockchainTxPayload == null) {
                            // status cases: RECEIVED
                            auditRecord.setEntityHash("");
                            auditRecord.setEntityHashLink("");
                            auditRecord.setDataLocation("");
                        } else {
                            // status cases: CREATED, PUBLISHED
                            auditRecord.setEntityHash(calculateSHA256(objectMapper.writeValueAsString(dataMap)));
                            dataLocation = blockchainTxPayload.dataLocation();
                            auditRecord.setEntityHashLink(extractHashLinkFromDataLocation(dataLocation));
                            auditRecord.setDataLocation(dataLocation);
                        }
                        // Firstly, we calculate the hash of the entity without the hash and hashLink fields
                        String auditRecordHash = calculateSHA256(objectMapper.writeValueAsString(auditRecord));
                        auditRecord.setHash(auditRecordHash);
                        // Then, we calculate the hashLink of the entity concatenating the previous hashLink
                        // with the hash of the current entity
                        auditRecord.setHashLink(setAuditRecordHashLink(lastAuditRecordRegistered, auditRecordHash));
                    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
                        log.warn("ProcessID: {} - Error building and saving audit record: {}", processId, e.getMessage());
                        return Mono.error(e);
                    }
                    return auditRecordRepository.save(auditRecord)
                            .doOnSuccess(unused -> log.info("ProcessID: {} - Audit record saved successfully. - Status: {}", processId, status))
                            .then();
                })
                .then();
    }

    /**
     * Create a new AuditRecord with status RETRIEVED or PUBLISHED and trader CONSUMER
     * from the retrieved external broker entity in string format.
     * If the retrievedBrokerEntity is null or blank, the status will be RECEIVED.
     */
    @Override
    public Mono<Void> buildAndSaveAuditRecordFromBlockchainNotification(String processId, BlockchainNotification blockchainNotification,
                                                                        String retrievedBrokerEntity, AuditRecordStatus status) {
        // Extract the entity ID from the data location
        String entityId = extractEntityIdFromDataLocation(blockchainNotification.dataLocation());
        // Get the most recent audit record for the entityId and get the most recent audit record overall
        return fetchMostRecentAuditRecord()
                .flatMap(lastAuditRecordRegistered -> {
                    try {
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
                            entityHash = calculateSHA256(retrievedBrokerEntity);
                            entityHashLink = extractHashLinkFromDataLocation(blockchainNotification.dataLocation());
                        }
                        // Create the new audit record
                        AuditRecord auditRecord = AuditRecord.builder()
                                .id(UUID.randomUUID())
                                .processId(processId)
                                .createdAt(Timestamp.from(Instant.now()))
                                .entityId(entityId)
                                .entityType(blockchainNotification.eventType())
                                .entityHash(entityHash)
                                .entityHashLink(entityHashLink)
                                .dataLocation(blockchainNotification.dataLocation())
                                .status(status)
                                .trader(AuditRecordTrader.CONSUMER)
                                .hash("")
                                .hashLink("")
                                .newTransaction(true)
                                .build();
                        // Firstly, we calculate the hash of the entity without the hash and hashLink fields
                        String auditRecordHash = calculateSHA256(objectMapper.writeValueAsString(auditRecord));
                        auditRecord.setHash(auditRecordHash);
                        // Then, we calculate the hashLink of the entity concatenating the previous hashLink
                        // with the hash of the current entity
                        auditRecord.setHashLink(setAuditRecordHashLink(lastAuditRecordRegistered, auditRecordHash));
                        return auditRecordRepository.save(auditRecord)
                                .doOnSuccess(unused -> log.info("ProcessID: {} - Audit record saved successfully. - Status: {}", processId, status))
                                .then();
                    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
                        return Mono.error(e);
                    }
                });
    }

    @Override
    public Mono<Void> buildAndSaveAuditRecordForSubEntity(String processId, String entityId, String entityType,
                                                          String retrievedBrokerEntity,
                                                          AuditRecordStatus status) {
        // Get the most recent audit record for the entityId and get the most recent audit record overall
        return fetchMostRecentAuditRecord()
                .flatMap(lastAuditRecordRegistered ->
                        findLatestConsumerPublishedAuditRecordByEntityId(processId, entityId)
                                .flatMap(previousAuditRecord -> {
                                    String previousHashLink = previousAuditRecord.getEntityHashLink();
                                    String entityHash = calculateHash(retrievedBrokerEntity);
                                    return calculateHashLink(Mono.just(previousHashLink), Mono.just(entityHash))
                                            .flatMap(entityHashLink -> {
                                                try {
                                                    // Create the new audit record
                                                    AuditRecord auditRecord = AuditRecord.builder()
                                                            .id(UUID.randomUUID())
                                                            .processId(processId)
                                                            .createdAt(Timestamp.from(Instant.now()))
                                                            .entityId(entityId)
                                                            .entityType(entityType)
                                                            .entityHash(entityHash)
                                                            .entityHashLink(entityHashLink)
                                                            .dataLocation("")
                                                            .status(status)
                                                            .trader(AuditRecordTrader.CONSUMER)
                                                            .hash("")
                                                            .hashLink("")
                                                            .newTransaction(true)
                                                            .build();
                                                    // Firstly, we calculate the hash of the entity without the hash and hashLink fields
                                                    String auditRecordHash = calculateSHA256(objectMapper.writeValueAsString(auditRecord));
                                                    auditRecord.setHash(auditRecordHash);
                                                    // Then, we calculate the hashLink of the entity concatenating the previous hashLink
                                                    // with the hash of the current entity
                                                    auditRecord.setHashLink(setAuditRecordHashLink(lastAuditRecordRegistered, auditRecordHash));
                                                    return auditRecordRepository.save(auditRecord)
                                                            .doOnSuccess(unused -> log.info("ProcessID: {} - Audit record for sub-entity saved successfully. - Status: {}", processId, status))
                                                            .then();
                                                } catch (JsonProcessingException |
                                                         NoSuchAlgorithmException e) {
                                                    return Mono.error(e);
                                                }
                                            });
                                }));
    }

    /**
     * Create a new AuditRecord with status RETRIEVED and trader CONSUMER
     * from the retrieved external sync entity in string format.
     * If the retrievedBrokerEntity is null or blank, the status will be RECEIVED.
     */
    @Override
    public Mono<Void> buildAndSaveAuditRecordFromDataSync(String processId, String issuer, MVAuditServiceEntity4DataNegotiation mvAuditServiceEntity4DataNegotiation, AuditRecordStatus status) {
        return fetchMostRecentAuditRecord()
                .flatMap(lastAuditRecordRegistered -> {
                    try {
                        String entityHash = mvAuditServiceEntity4DataNegotiation.hash();
                        String entityHashLink = mvAuditServiceEntity4DataNegotiation.hashlink();

                        AuditRecord auditRecord =
                                AuditRecord.builder()
                                        .id(UUID.randomUUID())
                                        .processId(processId)
                                        .createdAt(Timestamp.from(Instant.now()))
                                        .entityId(mvAuditServiceEntity4DataNegotiation.id())
                                        .entityType(mvAuditServiceEntity4DataNegotiation.type())
                                        .entityHash(entityHash)
                                        .entityHashLink(entityHashLink)
                                        .dataLocation("")
                                        .status(status)
                                        .trader(AuditRecordTrader.CONSUMER)
                                        .hash("")
                                        .hashLink("")
                                        .newTransaction(true)
                                        .build();

                        String auditRecordHash = calculateSHA256(objectMapper.writeValueAsString(auditRecord));
                        auditRecord.setHash(auditRecordHash);
                        auditRecord.setHashLink(setAuditRecordHashLink(lastAuditRecordRegistered, auditRecordHash));

                        log.debug("ProcessID: {} - Audit Record to save: {}", processId, auditRecord);

                        return auditRecordRepository.save(auditRecord).then();

                    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
                        return Mono.error(e);
                    }
                });
    }

    private static String getDataLocationForProducedEntity(String issuer, MVAuditServiceEntity4DataNegotiation mvAuditServiceEntity4DataNegotiation) {
        return issuer +
                "/api/v1/entities/" +
                mvAuditServiceEntity4DataNegotiation.id() +
                HASHLINK_PREFIX
                + mvAuditServiceEntity4DataNegotiation.hashlink();
    }

    /**
     * Fetches the most recently registered audit record. If no Audit Record exists, then
     * checks if the AuditRecord table is empty. If it is, then return a Mono.empty() because
     * understand that there are no Audit Records to fetch. If the table is not empty, then
     * return a Mono.error() with a NoSuchElementException.
     *
     * @return A Mono containing the most recent AuditRecord, or Mono.empty() if none exists.
     */
    @Override
    public Mono<AuditRecord> fetchMostRecentAuditRecord() {
        return auditRecordRepository.findMostRecentAuditRecord()
                .switchIfEmpty(Mono.defer(() -> auditRecordRepository.count().flatMap(count ->
                        count == 0 ? Mono.just(AuditRecord.builder().build())
                                : Mono.error(new NoSuchElementException()))));
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

    @Override
    public Mono<List<MVAuditServiceEntity4DataNegotiation>> findCreateOrUpdateAuditRecordsByEntityIds(
            String processId,
            String entityType,
            Mono<List<String>> entityIdsMono) {

        return entityIdsMono.flatMap(entityIds -> {
                    if (entityIds.isEmpty()) {
                        return Mono.empty();
                    }

                    return auditRecordRepository.findMostRecentPublishedAuditRecordsByEntityIds(entityIds)
                            .collectMap(AuditRecord::getEntityId)
                            .flatMap(auditRecordMap ->
                                    Flux.fromIterable(entityIds)
                                            .concatMap(id -> {
                                                System.out.println("Holaaaaa, aquiii");
                                                Mono<String> entityHashMono = getEntityHash(processId, Mono.just(id));

                                                return entityHashMono.flatMap(entityHash -> {
                                                    AuditRecord auditRecord = auditRecordMap.get(id);

                                                    if (auditRecord != null) {
                                                        log.debug("ProcessID: {} - AuditId: {}", processId, id);

                                                        return findOrUpdateAuditRecord(processId, entityHash, auditRecord);
                                                    } else {
                                                        return buildAndSaveAuditRecordFromUnregisteredOrOutdatedEntity(
                                                                processId,
                                                                new MVAuditServiceEntity4DataNegotiation(
                                                                        id,
                                                                        entityType,
                                                                        entityHash,
                                                                        entityHash
                                                                ),
                                                                AuditRecordTrader.PRODUCER,
                                                                null
                                                        );
                                                    }
                                                });
                                            })
                                            .collectList()
                            );
                }
        );
    }

    private Mono<MVAuditServiceEntity4DataNegotiation> findOrUpdateAuditRecord(String processId, String entityHash, AuditRecord auditRecord) {
        if (entityHash.equals(auditRecord.getEntityHash())) {
            return Mono.just(new MVAuditServiceEntity4DataNegotiation(
                    auditRecord.getEntityId(),
                    auditRecord.getEntityType(),
                    auditRecord.getEntityHash(),
                    auditRecord.getEntityHashLink()
            ));
        } else {
            return calculateHashLink(Mono.just(auditRecord.getEntityHashLink()), Mono.just(entityHash))
                    .flatMap(calculatedHashLink -> {
                        String newAuditRecordDataLocation =
                                auditRecord.getTrader().equals(AuditRecordTrader.CONSUMER) ? "" : auditRecord.getDataLocation();

                        return buildAndSaveAuditRecordFromUnregisteredOrOutdatedEntity(
                                processId,
                                new MVAuditServiceEntity4DataNegotiation(
                                        auditRecord.getEntityId(),
                                        auditRecord.getEntityType(),
                                        entityHash,
                                        calculatedHashLink
                                ),
                                auditRecord.getTrader(),
                                newAuditRecordDataLocation
                        );
                    });
        }
    }

    private Mono<MVAuditServiceEntity4DataNegotiation> buildAndSaveAuditRecordFromUnregisteredOrOutdatedEntity(String processId, MVAuditServiceEntity4DataNegotiation mvAuditServiceEntity4DataNegotiation, AuditRecordTrader trader, String dataLocation) {
        return fetchMostRecentAuditRecord()
                .flatMap(lastAuditRecordRegistered -> {
                    String newDataLocation = Objects.requireNonNullElseGet(
                            dataLocation,
                            () ->
                                    getDataLocationForProducedEntity(apiConfig.getExternalDomain(), mvAuditServiceEntity4DataNegotiation));
                    try {
                        AuditRecord auditRecord =
                                AuditRecord.builder()
                                        .id(UUID.randomUUID())
                                        .processId(processId)
                                        .createdAt(Timestamp.from(Instant.now()))
                                        .entityId(mvAuditServiceEntity4DataNegotiation.id())
                                        .entityType(mvAuditServiceEntity4DataNegotiation.type())
                                        .entityHash(mvAuditServiceEntity4DataNegotiation.hash())
                                        .entityHashLink(mvAuditServiceEntity4DataNegotiation.hashlink())
                                        .dataLocation(newDataLocation)
                                        .status(AuditRecordStatus.PUBLISHED)
                                        .trader(trader)
                                        .hash("")
                                        .hashLink("")
                                        .newTransaction(true)
                                        .build();

                        String auditRecordHash = calculateSHA256(objectMapper.writeValueAsString(auditRecord));
                        auditRecord.setHash(auditRecordHash);
                        auditRecord.setHashLink(setAuditRecordHashLink(lastAuditRecordRegistered, auditRecordHash));

                        log.debug("ProcessID: {} - Audit Record from unregistered or outdated entity to save: {}", processId, auditRecord);

                        return auditRecordRepository.save(auditRecord)
                                .thenReturn(new MVAuditServiceEntity4DataNegotiation(
                                        auditRecord.getEntityId(),
                                        auditRecord.getEntityType(),
                                        auditRecord.getEntityHash(),
                                        auditRecord.getEntityHashLink()));

                    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
                        return Mono.error(e);
                    }
                });
    }

    private String setAuditRecordHashLink(AuditRecord lastAuditRecordRegistered, String auditRecordHash)
            throws NoSuchAlgorithmException, JsonProcessingException {
        return lastAuditRecordRegistered.getHashLink() == null ? auditRecordHash
                : ApplicationUtils.calculateHashLink(lastAuditRecordRegistered.getHashLink(), auditRecordHash);
    }

    private Mono<String> getEntityHash(String processId, Mono<String> entityIdMono) {
        return entityIdMono.flatMap(entityId ->
                brokerPublisherService.getEntityById(processId, entityId)
                        .flatMap(entity -> {
                            try {
                                String hash = ApplicationUtils.calculateSHA256(entity);
                                return Mono.just(hash);
                            } catch (NoSuchAlgorithmException | JsonProcessingException e) {
                                return Mono.error(e);
                            }
                        }));
    }

    private Mono<String> calculateHashLink(Mono<String> previousHashlink, Mono<String> currentHash) {
        return previousHashlink
                .zipWith(currentHash)
                .flatMap(tuple -> {
                    try {
                        return Mono.just(ApplicationUtils.calculateHashLink(tuple.getT1(), tuple.getT2()));
                    } catch (NoSuchAlgorithmException | JsonProcessingException e) {
                        return Mono.error(e);
                    }
                });
    }

    private String calculateHash(String retrievedBrokerEntity) {
        try {
            return ApplicationUtils.calculateSHA256(retrievedBrokerEntity);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new JsonReadingException(e.getMessage());
        }
    }

}