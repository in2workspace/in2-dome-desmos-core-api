package es.in2.desmos.domain.services.api;

import es.in2.desmos.domain.models.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

public interface AuditRecordService {

    Mono<Void> buildAndSaveAuditRecordFromBrokerNotification(String processId, Map<String, Object> dataMap, AuditRecordStatus status, BlockchainTxPayload blockchainTxPayload);

    Mono<Void> buildAndSaveAuditRecordFromBlockchainNotification(String processId, BlockchainNotification blockchainNotification, String retrievedBrokerEntity, AuditRecordStatus status);

    /**
     * Records as RETRIEVED/CONSUMER the entities about to be written to the local broker without an audit trail of
     * their own, so the broker notification of that write is recognised as self-generated. Must complete before the
     * write. Skips the entities whose last RETRIEVED or DELETED record already has the hash of the body.
     *
     * @return the number of records written: the entities given minus the ones skipped
     */
    Mono<Integer> saveImplicitConsumerRetrieved(String processId, List<ImplicitRetrievedEntity> entities);

    Mono<Void> buildAndSaveAuditRecordsFromDataSync(String processId, List<MVAuditServiceEntity4DataNegotiation> entities, AuditRecordStatus status);

    Mono<AuditRecord> findMostRecentRetrievedOrDeletedByEntityId(String processId, String entityId);

    Mono<AuditRecord> getLastPublishedAuditRecordForProducerByEntityId(String processId, String entityId);

    Mono<String> fetchLatestProducerEntityHashLinkByEntityId(String processId, String entityId);

    Mono<AuditRecord> findLatestConsumerPublishedAuditRecordByEntityId(String processId, String entityId);

    Mono<AuditRecord> findLatestConsumerPublishedAuditRecord(String processId);

    Flux<MVAuditServiceEntity4DataNegotiation> findCreateOrUpdateAuditRecordsByEntityIds(String processId, String entityType, Flux<String> entityIdsFlux);
}