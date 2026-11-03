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
 * EVM Adapter Properties
 *
 * @param provider       - provider of the EVM adapter
 * @param internalDomain - internal domain
 * @param externalDomain - external domain
 * @param paths          - paths
 * @param responseTimeout - max time without receiving data from the adapter (subscriptions, events)
 * @param publicationTimeout - max time waiting for the answer to a publication. Much longer than responseTimeout:
 *                           the adapter answers once the transaction is confirmed, and a publication cut by this
 *                           timeout is not retried and its outcome is unknown (it may still reach the blockchain)
 */
@ConfigurationProperties(prefix = "dlt-adapter")
public record DLTAdapterProperties(@NotBlank String provider, String internalDomain, @NotBlank String externalDomain,
                                   @Valid @NotNull @NestedConfigurationProperty DLTAdapterPathProperties paths,
                                   Duration responseTimeout, Duration publicationTimeout) {

    private static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration DEFAULT_PUBLICATION_TIMEOUT = Duration.ofMinutes(10);


    @ConstructorBinding
    public DLTAdapterProperties(String provider, String internalDomain, String externalDomain, DLTAdapterPathProperties paths,
                                Duration responseTimeout, Duration publicationTimeout) {
        this.provider = provider;
        this.internalDomain = internalDomain;
        this.externalDomain = externalDomain;
        this.paths = Optional.ofNullable(paths).orElse(new DLTAdapterPathProperties(null, null, null));
        this.responseTimeout = Optional.ofNullable(responseTimeout).orElse(DEFAULT_RESPONSE_TIMEOUT);
        this.publicationTimeout = Optional.ofNullable(publicationTimeout).orElse(DEFAULT_PUBLICATION_TIMEOUT);
    }

    public record DLTAdapterPathProperties(@NotBlank String publication, @NotBlank String subscription, @NotBlank String events) {}

}
