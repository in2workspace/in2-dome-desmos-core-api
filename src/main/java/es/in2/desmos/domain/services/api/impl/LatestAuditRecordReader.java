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
 * Shared by {@link AuditRecordServiceImpl} (notifications) and {@link AuditChainBatchWriter} (P2P sync), so both read
 * {@code created_at} through the same Spring Data converters. It lives in its own component because the writer cannot
 * depend on the service, which depends on the writer.
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
