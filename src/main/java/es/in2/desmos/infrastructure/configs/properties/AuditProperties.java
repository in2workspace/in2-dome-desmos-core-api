package es.in2.desmos.infrastructure.configs.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

/**
 * Settings for the writes of the audit chain.
 *
 * @param batchSize   - audit records written per transaction (one chain lock, one read of the last record). Between
 *                    {@value #MIN_BATCH_SIZE} and {@value #MAX_BATCH_SIZE}: each row takes 12 bind parameters and a
 *                    statement must stay well below PostgreSQL's 65,535 parameters. Above 500 nothing is gained.
 * @param lockTimeout - how long a write waits for the chain lock held by another transaction (PostgreSQL's
 *                    {@code lock_timeout}, set for the write's transaction only). Notifications wait for it too, so a
 *                    stuck lock holder must not block them forever. Between 1 ms and 1 hour; 30 s by default.
 */
@ConfigurationProperties(prefix = "audit")
public record AuditProperties(Integer batchSize, Duration lockTimeout) {

    public static final int DEFAULT_BATCH_SIZE = 500;
    public static final int MIN_BATCH_SIZE = 1;
    public static final int MAX_BATCH_SIZE = 2_000;
    public static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration MAX_LOCK_TIMEOUT = Duration.ofHours(1);

    @ConstructorBinding
    public AuditProperties {
        if (batchSize == null) {
            batchSize = DEFAULT_BATCH_SIZE;
        }
        if (batchSize < MIN_BATCH_SIZE || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("audit.batch-size must be between " + MIN_BATCH_SIZE + " and "
                    + MAX_BATCH_SIZE + ", got " + batchSize);
        }
        if (lockTimeout == null) {
            lockTimeout = DEFAULT_LOCK_TIMEOUT;
        }
        // Zero would disable the timeout in PostgreSQL
        if (lockTimeout.toMillis() < 1 || lockTimeout.compareTo(MAX_LOCK_TIMEOUT) > 0) {
            throw new IllegalArgumentException("audit.lock-timeout must be between 1ms and " + MAX_LOCK_TIMEOUT
                    + ", got " + lockTimeout);
        }
    }

    public AuditProperties(Integer batchSize) {
        this(batchSize, null);
    }
}
