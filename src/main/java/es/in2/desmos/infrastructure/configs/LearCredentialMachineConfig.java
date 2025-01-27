package es.in2.desmos.infrastructure.configs;

import es.in2.desmos.infrastructure.configs.properties.SecurityProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class LearCredentialMachineConfig {
    private static final String CLIENT_CREDENTIALS_GRANT_TYPE_VALUE = "client_credentials";
    private static final String CLIENT_ASSERTION_TYPE_VALUE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    private static final String CLIENT_ASSERTION_EXPIRATION = "2";
    private static final String CLIENT_ASSERTION_EXPIRATION_UNIT_TIME = "MINUTES";

    private final SecurityProperties securityProperties;

    public String getClientCredentialsGrantTypeValue() {
        return CLIENT_CREDENTIALS_GRANT_TYPE_VALUE;
    }

    public String getClientAssertionTypeValue() {
        return CLIENT_ASSERTION_TYPE_VALUE;
    }

    public String getClientAssertionExpiration() {
        return CLIENT_ASSERTION_EXPIRATION;
    }

    public String getClientAssertionExpirationUnitTime() {
        return CLIENT_ASSERTION_EXPIRATION_UNIT_TIME;
    }

    public String getLearCredentialMachineInBase64() {
        return securityProperties.learCredentialMachineInBase64();
    }
}
