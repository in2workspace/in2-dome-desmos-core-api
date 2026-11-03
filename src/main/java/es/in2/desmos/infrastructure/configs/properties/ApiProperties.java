package es.in2.desmos.infrastructure.configs.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Optional;

/**
 * @param externalDomain  - external domain of this access node
 * @param version         - API version
 * @param maxInMemorySize - max bytes the shared WebClient buffers in memory per response (and per
 *                          streamed JSON object); the WebClient default of 256KB is too small for
 *                          large NGSI-LD entities exchanged during P2P sync
 * @param connectTimeout  - max time to establish a TCP connection from any outgoing WebClient
 * @param responseTimeout - max time without receiving data from a peer node (shared WebClient); it is
 *                          measured between reads, so long NDJSON streams are not cut while data flows
 */
@ConfigurationProperties(prefix = "api")
public record ApiProperties(@NotBlank String externalDomain, @NotBlank String version, DataSize maxInMemorySize,
                            Duration connectTimeout, Duration responseTimeout) {

    public ApiProperties(String externalDomain, String version, DataSize maxInMemorySize,
                         Duration connectTimeout, Duration responseTimeout) {
        this.externalDomain = externalDomain;
        this.version = version;
        this.maxInMemorySize = Optional.ofNullable(maxInMemorySize).orElse(DataSize.ofMegabytes(10));
        this.connectTimeout = Optional.ofNullable(connectTimeout).orElse(Duration.ofSeconds(10));
        this.responseTimeout = Optional.ofNullable(responseTimeout).orElse(Duration.ofSeconds(300));
    }
}
