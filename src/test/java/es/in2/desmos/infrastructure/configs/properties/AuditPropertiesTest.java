package es.in2.desmos.infrastructure.configs.properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AuditPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(AuditProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void theBatchSizeDefaultsTo500() {
        runner.run(context -> assertThat(context.getBean(AuditProperties.class).batchSize()).isEqualTo(500));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2000})
    void itShouldAcceptTheLimits(int batchSize) {
        runner.withPropertyValues("audit.batch-size=" + batchSize)
                .run(context -> assertThat(context.getBean(AuditProperties.class).batchSize()).isEqualTo(batchSize));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 2001})
    void theApplicationShouldNotStartWithABatchSizeOutOfRange(int batchSize) {
        runner.withPropertyValues("audit.batch-size=" + batchSize)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("audit.batch-size must be between 1 and 2000"));
    }

    @Test
    void theLockTimeoutDefaultsTo30Seconds() {
        runner.run(context -> assertThat(context.getBean(AuditProperties.class).lockTimeout()).isEqualTo(Duration.ofSeconds(30)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1ms", "500ms", "1h"})
    void itShouldAcceptALockTimeoutWithinTheLimits(String lockTimeout) {
        runner.withPropertyValues("audit.lock-timeout=" + lockTimeout)
                .run(context -> assertThat(context.getBean(AuditProperties.class).lockTimeout())
                        .isEqualTo(DurationStyle.detectAndParse(lockTimeout)));
    }

    // 0 would disable PostgreSQL's lock_timeout
    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s", "61m"})
    void theApplicationShouldNotStartWithALockTimeoutOutOfRange(String lockTimeout) {
        runner.withPropertyValues("audit.lock-timeout=" + lockTimeout)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("audit.lock-timeout must be between 1ms and PT1H"));
    }
}
