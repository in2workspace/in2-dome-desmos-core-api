package es.in2.desmos.infrastructure.configs.properties;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security")
public record SecurityProperties(@NotNull String privateKey, @NotNull String learCredentialMachineInBase64) {
}
