package es.in2.desmos.domain.services.api.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.utils.ApplicationUtils;
import es.in2.desmos.infrastructure.configs.properties.AuditProperties;
import io.r2dbc.spi.R2dbcException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static es.in2.desmos.domain.utils.LogUtils.AUDIT;

/**
 * Appends the audit records of the P2P synchronization to the global audit chain in batches.
 * <p>
 * The drafts are split into chunks of {@code audit.batch-size}, written in order. Each chunk is one transaction that
 * takes the chain lock, reads the last record once, sets {@code createdAt}, {@code hash} and {@code hashLink} of every
 * row in memory and inserts them all. Chunks of this replica take turns in memory before opening their transaction, so
 * waiting for the lock holds no pooled connection; the advisory lock serializes them across replicas.
 * <p>
 * Notifications do not take the lock, so a notification may still fork the chain against a sync chunk.
 */
@Slf4j
@Component
public class AuditChainBatchWriter {

    private static final int LOGGED_HASH_LENGTH = 12;

    private final LatestAuditRecordReader latestAuditRecordReader;
    private final AuditRecordStatements statements;
    private final ObjectMapper objectMapper;
    private final TransactionalOperator transactionalOperator;
    private final ReactiveMutex localMutex;
    private final Clock clock;
    private final int batchSize;

    @Autowired
    AuditChainBatchWriter(LatestAuditRecordReader latestAuditRecordReader, AuditRecordStatements statements,
                          ObjectMapper objectMapper, ReactiveTransactionManager transactionManager,
                          AuditProperties auditProperties) {
        this(latestAuditRecordReader, statements, objectMapper, transactionManager, auditProperties,
                new ReactiveMutex(), Clock.systemUTC());
    }

    AuditChainBatchWriter(LatestAuditRecordReader latestAuditRecordReader, AuditRecordStatements statements,
                          ObjectMapper objectMapper, ReactiveTransactionManager transactionManager,
                          AuditProperties auditProperties, ReactiveMutex localMutex, Clock clock) {
        this.latestAuditRecordReader = latestAuditRecordReader;
        this.statements = statements;
        this.objectMapper = objectMapper;
        this.transactionalOperator = TransactionalOperator.create(transactionManager, transactionDefinition());
        this.localMutex = localMutex;
        this.clock = clock;
        this.batchSize = auditProperties.batchSize();
    }

    // READ COMMITTED, explicitly: under REPEATABLE READ the snapshot would be taken by the lock statement, before the
    // wait ends, and the read of the last record would miss what the previous lock holder committed.
    private static TransactionDefinition transactionDefinition() {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        definition.setName("audit-chain-batch");
        return definition;
    }

    /**
     * Appends the drafts to the audit chain, in order.
     *
     * @param drafts audit records built as for a single save (including {@code newTransaction(true)}) but without
     *               {@code createdAt}, {@code hash} and {@code hashLink}. They are not modified.
     * @param source flow name for the AUDIT log lines
     */
    public Mono<Void> append(String processId, List<AuditRecord> drafts, String source) {
        if (drafts.isEmpty()) {
            return Mono.empty();
        }
        List<AuditRecord> rows = List.copyOf(drafts);
        List<List<AuditRecord>> chunks = new ArrayList<>();
        for (int from = 0; from < rows.size(); from += batchSize) {
            chunks.add(rows.subList(from, Math.min(from + batchSize, rows.size())));
        }
        return Flux.fromIterable(chunks)
                .concatMap(chunk -> appendChunk(processId, chunk, source))
                .then();
    }

    // The row-by-row fallback stays outside the permit: each row asks for its own (the mutex is not reentrant)
    private Mono<Void> appendChunk(String processId, List<AuditRecord> drafts, String source) {
        return appendInTx(processId, drafts, source)
                .onErrorResume(AuditChainBatchWriter::isRowDataError, batchError -> {
                    R2dbcException cause = (R2dbcException) batchError.getCause();
                    log.warn("{} ProcessID: {} - Batch of {} audit records failed (SQLSTATE {}: {}), writing it row by row",
                            AUDIT, processId, drafts.size(), cause.getSqlState(), cause.getMessage());
                    return Flux.fromIterable(drafts)
                            .concatMap(draft -> appendInTx(processId, List.of(draft), source))
                            .then(Mono.defer(() -> {
                                log.error("{} ProcessID: {} - Batch of {} audit records failed but every row was written "
                                                + "on its own: the failure came from the batch, not from a row",
                                        AUDIT, processId, drafts.size(), batchError);
                                return Mono.error(batchError);
                            }));
                });
    }

    private Mono<Void> appendInTx(String processId, List<AuditRecord> drafts, String source) {
        return localMutex.withPermit(
                        statements.lockChain()
                                .then(latestAuditRecordReader.fetch())
                                .flatMap(last -> {
                                    // Built here, after the lock and the read: createdAt is taken inside the lock and
                                    // every attempt (batch, then row by row) starts from clean copies of the drafts
                                    List<AuditRecord> chained;
                                    try {
                                        chained = chain(last, drafts);
                                    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
                                        return Mono.error(e);
                                    }
                                    return statements.insert(chained).thenReturn(chained);
                                })
                                .as(transactionalOperator::transactional))
                // After the commit: a chunk that rolls back leaves no AUDIT lines
                .doOnNext(saved -> saved.forEach(auditRecord -> logSaved(processId, auditRecord, source)))
                .then();
    }

    private List<AuditRecord> chain(AuditRecord last, List<AuditRecord> drafts)
            throws JsonProcessingException, NoSuchAlgorithmException {
        List<AuditRecord> chained = new ArrayList<>(drafts.size());
        Instant previousCreatedAt = last.getCreatedAt() == null ? null : last.getCreatedAt().toInstant();
        String previousHashLink = last.getHashLink();
        for (AuditRecord draft : drafts) {
            Instant createdAt = nextCreatedAt(previousCreatedAt);
            AuditRecord auditRecord = draft.toBuilder()
                    .createdAt(Timestamp.from(createdAt))
                    .hash("")
                    .hashLink("")
                    .build();
            // Same serialization as a single save: hash and hashLink empty while hashing
            String hash = ApplicationUtils.calculateSHA256(objectMapper.writeValueAsString(auditRecord));
            auditRecord.setHash(hash);
            auditRecord.setHashLink(previousHashLink == null ? hash
                    : ApplicationUtils.calculateHashLink(previousHashLink, hash));
            chained.add(auditRecord);
            previousCreatedAt = createdAt;
            previousHashLink = auditRecord.getHashLink();
        }
        return chained;
    }

    // Strictly increasing, so ORDER BY created_at DESC LIMIT 1 always finds the real last row: the current time, or
    // 1 µs after the previous row if the clock has not moved on (or went back). Truncated to microseconds, the precision
    // of TIMESTAMP, so the value compared with the next row is the stored one. Read for every row, so rows do not run
    // ahead of the clock.
    private Instant nextCreatedAt(Instant previousCreatedAt) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (previousCreatedAt == null) {
            return now;
        }
        Instant minimum = previousCreatedAt.truncatedTo(ChronoUnit.MICROS).plus(1, ChronoUnit.MICROS);
        return now.isBefore(minimum) ? minimum : now;
    }

    // SQLSTATE class 22 (data exception) or 23 (integrity constraint violation): the failure comes from the data of a
    // row. Class 22 reaches Spring as BadSqlGrammarException (r2dbc-postgresql maps it to PostgresqlBadGrammarException),
    // so the Spring exception type cannot tell it apart from a real syntax error.
    static boolean isRowDataError(Throwable e) {
        return e instanceof DataAccessException
                && e.getCause() instanceof R2dbcException r2dbc
                && r2dbc.getSqlState() != null
                && (r2dbc.getSqlState().startsWith("22") || r2dbc.getSqlState().startsWith("23"));
    }

    // Same format as AuditRecordServiceImpl.saveAndLog
    private static void logSaved(String processId, AuditRecord auditRecord, String source) {
        log.info("{} ProcessID: {} - {} {} -> {} {} (source={}, entityHash={})",
                AUDIT, processId, auditRecord.getEntityType(), auditRecord.getEntityId(), auditRecord.getStatus(),
                auditRecord.getTrader(), source, shortHash(auditRecord.getEntityHash()));
    }

    private static String shortHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return "-";
        }
        return hash.length() > LOGGED_HASH_LENGTH ? hash.substring(0, LOGGED_HASH_LENGTH) : hash;
    }
}
