package es.in2.desmos.infrastructure.configs.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.util.Optional;

/**
 * @param externalDomain  - external domain of this access node
 * @param version         - API version
 * @param maxInMemorySize - max bytes the shared WebClient buffers in memory per response (and per
 *                          streamed JSON object); the WebClient default of 256KB is too small for
 *                          large NGSI-LD entities exchanged during P2P sync
 */
@ConfigurationProperties(prefix = "api")
public record ApiProperties(@NotBlank String externalDomain, @NotBlank String version, DataSize maxInMemorySize) {

    public ApiProperties(String externalDomain, String version, DataSize maxInMemorySize) {
        this.externalDomain = externalDomain;
        this.version = version;
        this.maxInMemorySize = Optional.ofNullable(maxInMemorySize).orElse(DataSize.ofMegabytes(10));
    }
}
