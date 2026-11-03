package es.in2.desmos.infrastructure.configs.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Optional;

/**
 * Settings for Desmos' own log output.
 *
 * @param payloadMaxSize              - max size of a payload written to the log (DEBUG); longer payloads are truncated
 * @param slowRequestThreshold        - requests to a genesis node slower than this are logged with a WARN
 */
@ConfigurationProperties(prefix = "desmos.logging")
public record LoggingProperties(DataSize payloadMaxSize, Duration slowRequestThreshold) {

    public LoggingProperties(DataSize payloadMaxSize, Duration slowRequestThreshold) {
        this.payloadMaxSize = Optional.ofNullable(payloadMaxSize).orElse(DataSize.ofKilobytes(2));
        this.slowRequestThreshold = Optional.ofNullable(slowRequestThreshold).orElse(Duration.ofSeconds(30));
    }

    public int payloadMaxChars() {
        return (int) Math.min(Integer.MAX_VALUE, payloadMaxSize.toBytes());
    }
}
