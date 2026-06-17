package es.in2.desmos.infrastructure.configs.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.unit.DataSize;

import java.util.Optional;

/**
 * Configuration intended to connect the NGSI-LD ContextBroker
 *
 * @param provider        - context broker provider
 * @param internalDomain  - internal address of the broker, used to connect from within the connector
 * @param paths           - ngis-ld paths to be used when connecting the broker
 * @param maxInMemorySize - max bytes the broker WebClient buffers in memory per response;
 *                          the WebClient default of 256KB is too small for large NGSI-LD entities
 */
@ConfigurationProperties(prefix = "broker")
public record BrokerProperties(@NotBlank String provider, @NotBlank String internalDomain,
                               @Valid @NotNull @NestedConfigurationProperty BrokerPathProperties paths,
                               DataSize maxInMemorySize) {

    @ConstructorBinding
    public BrokerProperties(String provider, String internalDomain, BrokerPathProperties paths, DataSize maxInMemorySize) {
        this.provider = provider;
        this.internalDomain = internalDomain;
        this.paths = Optional.ofNullable(paths).orElse(new BrokerPathProperties(null, null, null, null));
        this.maxInMemorySize = Optional.ofNullable(maxInMemorySize).orElse(DataSize.ofMegabytes(10));
    }

    public record BrokerPathProperties(@NotBlank String entities, @NotBlank String entityOperations,
                                      @NotBlank String subscriptions, @NotBlank String temporal) {
    }

}
