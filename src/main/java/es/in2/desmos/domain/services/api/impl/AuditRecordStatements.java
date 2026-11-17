package es.in2.desmos.domain.services.api.impl;

import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.infrastructure.configs.properties.AuditProperties;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * The SQL of {@link AuditChainBatchWriter}: the chain lock and the multi-row INSERT. Both must run inside the writer's
 * transaction.
 */
@Component
class AuditRecordStatements {

    /**
     * Key of the transaction-level advisory lock that serializes the batch writes of the audit chain, across replicas.
     * Arbitrary, but distinct from Flyway's keys (77431708279161 + a hash of the schema). ASCII for "desmosAR".
     */
    static final long CHAIN_LOCK_KEY = 0x6465736D6F734152L;

    static final String LOCK_SQL = "SELECT 1 FROM pg_advisory_xact_lock($1)";

    private static final List<String> COLUMNS = List.of("id", "process_id", "created_at", "entity_id", "entity_type",
            "entity_hash", "entity_hashlink", "data_location", "status", "trader", "hash", "hashlink");

    // Sizes of the remainder statements; with batch-size they make at most four distinct INSERT texts
    private static final int[] REMAINDER_STATEMENT_SIZES = {100, 10, 1};

    private final DatabaseClient databaseClient;
    private final int batchSize;

    AuditRecordStatements(DatabaseClient databaseClient, AuditProperties auditProperties) {
        this.databaseClient = databaseClient;
        this.batchSize = auditProperties.batchSize();
    }

    /** Waits for the chain lock. It is released on commit or rollback. */
    Mono<Void> lockChain() {
        return databaseClient.sql(LOCK_SQL)
                .bind(0, CHAIN_LOCK_KEY)
                .map(row -> 1)
                .all()
                .then();
    }

    /**
     * Inserts the rows in order, with statements of a few fixed sizes only (see {@link #statementSizes}).
     */
    Mono<Void> insert(List<AuditRecord> rows) {
        List<List<AuditRecord>> statements = new ArrayList<>();
        int from = 0;
        for (int size : statementSizes(rows.size(), batchSize)) {
            statements.add(rows.subList(from, from + size));
            from += size;
        }
        return Flux.fromIterable(statements)
                .concatMap(this::insertStatement)
                .then();
    }

    /**
     * Splits {@code rows} into statements of {@code batchSize} rows and then 100, 10 and 1 rows (only the sizes not
     * larger than {@code batchSize}). The driver keeps one server-side prepared statement per distinct SQL text and
     * connection, without limit, and a multi-row INSERT is a distinct text for each row count; so a chunk is never
     * inserted with its exact size.
     */
    static List<Integer> statementSizes(int rows, int batchSize) {
        List<Integer> sizes = new ArrayList<>();
        int remaining = rows;
        for (int size : IntStream.concat(IntStream.of(batchSize), IntStream.of(REMAINDER_STATEMENT_SIZES))
                .filter(size -> size <= batchSize)
                .distinct()
                .toArray()) {
            while (remaining >= size) {
                sizes.add(size);
                remaining -= size;
            }
        }
        return sizes;
    }

    static String insertSql(int rows) {
        StringBuilder sql = new StringBuilder("INSERT INTO desmos.audit_records (")
                .append(String.join(", ", COLUMNS))
                .append(") VALUES ");
        int parameter = 1;
        for (int row = 0; row < rows; row++) {
            sql.append(row == 0 ? "(" : ", (");
            for (int column = 0; column < COLUMNS.size(); column++) {
                sql.append(column == 0 ? "$" : ", $").append(parameter++);
            }
            sql.append(')');
        }
        return sql.toString();
    }

    private Mono<Long> insertStatement(List<AuditRecord> rows) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(insertSql(rows.size()));
        int index = 0;
        for (AuditRecord row : rows) {
            spec = bind(spec, index++, row.getId(), UUID.class);
            spec = bind(spec, index++, row.getProcessId(), String.class);
            // Bound as Timestamp, like repository.save does: the driver's DateCodec converts it to the session's time
            // zone exactly as for the rows saved by the notifications
            spec = bind(spec, index++, row.getCreatedAt(), Timestamp.class);
            spec = bind(spec, index++, row.getEntityId(), String.class);
            spec = bind(spec, index++, row.getEntityType(), String.class);
            spec = bind(spec, index++, row.getEntityHash(), String.class);
            spec = bind(spec, index++, row.getEntityHashLink(), String.class);
            spec = bind(spec, index++, row.getDataLocation(), String.class);
            // Enums are stored by name(), as Spring Data does
            spec = bind(spec, index++, row.getStatus() == null ? null : row.getStatus().name(), String.class);
            spec = bind(spec, index++, row.getTrader() == null ? null : row.getTrader().name(), String.class);
            spec = bind(spec, index++, row.getHash(), String.class);
            spec = bind(spec, index++, row.getHashLink(), String.class);
        }
        return spec.fetch().rowsUpdated();
    }

    private static DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec spec, int index,
                                                          Object value, Class<?> type) {
        return value == null ? spec.bindNull(index, type) : spec.bind(index, value);
    }
}
