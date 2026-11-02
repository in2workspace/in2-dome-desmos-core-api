package es.in2.desmos.infrastructure.configs.properties;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingPropertiesTests {

    @Test
    void itShouldUseTheDefaultsWhenNotConfigured() {
        LoggingProperties loggingProperties = new LoggingProperties(null, null);

        assertThat(loggingProperties.payloadMaxSize()).isEqualTo(DataSize.ofKilobytes(2));
        assertThat(loggingProperties.payloadMaxChars()).isEqualTo(2048);
        assertThat(loggingProperties.slowRequestThreshold()).isEqualTo(Duration.ofSeconds(30));
    }
}
