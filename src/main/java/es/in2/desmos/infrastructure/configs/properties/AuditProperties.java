package es.in2.desmos.infrastructure.configs.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the audit records written by the P2P synchronization.
 *
 * @param batchSize - audit records written per transaction (one chain lock, one read of the last record). Between
 *                  {@value #MIN_BATCH_SIZE} and {@value #MAX_BATCH_SIZE}: each row takes 12 bind parameters and a
 *                  statement must stay well below PostgreSQL's 65,535 parameters. Above 500 nothing is gained.
 */
@ConfigurationProperties(prefix = "audit")
public record AuditProperties(Integer batchSize) {

    public static final int DEFAULT_BATCH_SIZE = 500;
    public static final int MIN_BATCH_SIZE = 1;
    public static final int MAX_BATCH_SIZE = 2_000;

    public AuditProperties {
        if (batchSize == null) {
            batchSize = DEFAULT_BATCH_SIZE;
        }
        if (batchSize < MIN_BATCH_SIZE || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("audit.batch-size must be between " + MIN_BATCH_SIZE + " and "
                    + MAX_BATCH_SIZE + ", got " + batchSize);
        }
    }
}
