package es.in2.desmos.infrastructure.configs.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the organization ID which instantiates the solution.
 *
 * @param organizationIdentifier - OrganizationID information
 */
@ConfigurationProperties(prefix = "operator")
public record OperatorProperties(@NotBlank String organizationIdentifier) {
}
