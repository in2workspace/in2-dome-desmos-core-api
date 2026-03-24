package es.in2.desmos.infrastructure.configs.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.Optional;

/**
 * EVM Adapter Properties
 *
 * @param provider       - provider of the EVM adapter
 * @param internalDomain - internal domain
 * @param externalDomain - external domain
 * @param paths          - paths
 */
@ConfigurationProperties(prefix = "dlt-adapter")
public record DLTAdapterProperties(@NotBlank String provider, String internalDomain, @NotBlank String externalDomain,
                                   @Valid @NotNull @NestedConfigurationProperty DLTAdapterPathProperties paths) {

    @ConstructorBinding
    public DLTAdapterProperties(String provider, String internalDomain, String externalDomain, DLTAdapterPathProperties paths) {
        this.provider = provider;
        this.internalDomain = internalDomain;
        this.externalDomain = externalDomain;
        this.paths = Optional.ofNullable(paths).orElse(new DLTAdapterPathProperties(null, null, null));
    }

    public record DLTAdapterPathProperties(@NotBlank String publication, @NotBlank String subscription, @NotBlank String events) {}

}
