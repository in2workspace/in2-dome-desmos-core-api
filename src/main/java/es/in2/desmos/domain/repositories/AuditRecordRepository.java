package es.in2.desmos.domain.repositories;

import es.in2.desmos.domain.models.AuditRecord;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@Repository
public interface AuditRecordRepository extends ReactiveCrudRepository<AuditRecord, UUID> {

    @Query("SELECT * FROM desmos.audit_records WHERE id=:uuid")
    Mono<AuditRecord> findByUUID(UUID uuid);

    /**
     * Retrieves the most recently created audit record from the database.
     *
     * @return A Mono emitting the latest AuditRecord based on the creation timestamp,
     * or Mono.empty() if no records are found.
     */
    @Query("SELECT * FROM desmos.audit_records ORDER BY created_at DESC LIMIT 1")
    Mono<AuditRecord> findMostRecentAuditRecord();

    /**
     * Finds the most recent audit record for a given entity ID where the record's status is either PUBLISHED or DELETED.
     * The records are ordered by their creation timestamp in descending order to ensure the most recent record is selected.
     *
     * @param entityId The ID of the entity for which the audit record is being searched.
     * @return A Mono that emits the most recent published or deleted audit record for the specified entity ID, if found.
     */
    @Query("SELECT * FROM desmos.audit_records WHERE entity_id = :entityId AND status = 'RETRIEVED' OR status = 'DELETED' ORDER BY created_at DESC LIMIT 1")
    Mono<AuditRecord> findMostRecentRetrievedOrDeletedByEntityId(String entityId);

    @Query("SELECT * FROM desmos.audit_records WHERE entity_id = :entityId AND status = 'PUBLISHED' ORDER BY created_at DESC LIMIT 1")
    Mono<AuditRecord> findMostRecentPublishedAuditRecordByEntityId(String entityId);

    @Query("""
       SELECT DISTINCT ON (ar.entity_id) ar.*
        FROM desmos.audit_records ar
        WHERE ar.entity_id = ANY(:entityIds)
        AND ar.status = 'PUBLISHED'
        ORDER BY ar.entity_id, ar.created_at DESC;
       """)
    Flux<AuditRecord> findMostRecentPublishedAuditRecordsByEntityIds(String[] entityIds);

    @Query("SELECT * FROM desmos.audit_records WHERE entity_id = :entityId AND status = 'PUBLISHED' AND trader = 'PRODUCER' ORDER BY created_at DESC LIMIT 1")
    Mono<AuditRecord> findLatestPublishedAuditRecordForProducerByEntityId(String entityId);

    @Query("SELECT * FROM desmos.audit_records WHERE entity_id = :entityId ORDER BY created_at DESC LIMIT 1")
    Flux<AuditRecord> findLastTransactionByEntityId(String entityId);

    Flux<AuditRecord> findByEntityId(final String entityId);

    @Query("SELECT * FROM desmos.audit_records WHERE entity_id = :entityId AND status = 'PUBLISHED' AND trader = 'CONSUMER' ORDER BY created_at DESC LIMIT 1")
    Mono<AuditRecord> findLastPublishedConsumerAuditRecordByEntityId(String entityId);

    @Query("SELECT * FROM desmos.audit_records WHERE status = 'PUBLISHED' AND trader = 'CONSUMER' ORDER BY created_at DESC LIMIT 1")
    Mono<AuditRecord> findLastPublishedConsumerAuditRecord();

    @Query("SELECT * FROM desmos.audit_records ORDER BY created_at DESC LIMIT 1 OFFSET 1")
    Mono<AuditRecord> findPreviousTransaction();

}