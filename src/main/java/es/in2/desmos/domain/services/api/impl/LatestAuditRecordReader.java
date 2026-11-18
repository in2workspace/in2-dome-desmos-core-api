package es.in2.desmos.domain.services.api.impl;

import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.repositories.AuditRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.NoSuchElementException;

/**
 * Reads the last audit record of the global chain (the one the next record links to).
 * <p>
 * Used by {@link AuditChainBatchWriter}, inside the chain lock. It reads {@code created_at} through the Spring Data
 * converters, the same that read the rows saved with {@code repository.save} before every write went through the
 * writer.
 */
@Component
@RequiredArgsConstructor
public class LatestAuditRecordReader {

    private final AuditRecordRepository auditRecordRepository;

    /**
     * @return the most recent audit record; an empty {@link AuditRecord} (every field {@code null}) if the table is
     * empty; or a {@link NoSuchElementException} if none is found although the table is not empty.
     */
    public Mono<AuditRecord> fetch() {
        return auditRecordRepository.findMostRecentAuditRecord()
                .switchIfEmpty(Mono.defer(() -> auditRecordRepository.count().flatMap(count ->
                        count == 0 ? Mono.just(AuditRecord.builder().build())
                                : Mono.error(new NoSuchElementException()))));
    }
}
