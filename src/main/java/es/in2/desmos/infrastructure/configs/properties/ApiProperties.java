package es.in2.desmos.infrastructure.configs.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "api")
public record ApiProperties(@NotBlank String externalDomain, @NotBlank String version) {
}
