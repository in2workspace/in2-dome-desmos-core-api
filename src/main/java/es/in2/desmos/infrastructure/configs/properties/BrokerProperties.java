package es.in2.desmos.infrastructure.configs.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;
import java.util.Optional;

/**
 * Configuration intended to connect the NGSI-LD ContextBroker
 *
 * @param provider       - context broker provider
 * @param internalDomain - internal address of the broker, used to connect from within the connector
 * @param paths          - ngis-ld paths to be used when connecting the broker
 * @param pageSize       - number of entities requested per page when paginating entity queries; must not
 *                       exceed the broker's own max query limit (e.g. Scorpio's {@code scorpio.entity.max-limit},
 *                       1000 by default), which rejects with 403 instead of truncating
 * @param responseTimeout - max time without receiving data from the broker before the request fails
 */
@ConfigurationProperties(prefix = "broker")
public record BrokerProperties(@NotBlank String provider, @NotBlank String internalDomain,
                               @Valid @NotNull @NestedConfigurationProperty BrokerPathProperties paths,
                               Integer pageSize, Duration responseTimeout) {

    private static final int DEFAULT_PAGE_SIZE = 1000;
    private static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(120);

    @ConstructorBinding
    public BrokerProperties(String provider, String internalDomain, BrokerPathProperties paths, Integer pageSize,
                            Duration responseTimeout) {
        this.provider = provider;
        this.internalDomain = internalDomain;
        this.paths = Optional.ofNullable(paths).orElse(new BrokerPathProperties(null, null, null, null));
        this.pageSize = Optional.ofNullable(pageSize).orElse(DEFAULT_PAGE_SIZE);
        this.responseTimeout = Optional.ofNullable(responseTimeout).orElse(DEFAULT_RESPONSE_TIMEOUT);
    }

    public record BrokerPathProperties(@NotBlank String entities, @NotBlank String entityOperations,
                                      @NotBlank String subscriptions, @NotBlank String temporal) {
    }

}
