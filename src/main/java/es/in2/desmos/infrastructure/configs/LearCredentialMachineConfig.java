package es.in2.desmos.infrastructure.configs;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import es.in2.desmos.infrastructure.configs.properties.SecurityProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

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

    /**
     * Expiration of the LEARCredentialMachine, read from its JWT without verifying it: the "exp" claim or,
     * failing that, the credential's validUntil / expirationDate. Only meant for logging.
     */
    public Optional<Instant> getLearCredentialMachineExpiration() {
        String credentialBase64 = getLearCredentialMachineInBase64();
        if (credentialBase64 == null || credentialBase64.isBlank()) {
            return Optional.empty();
        }
        try {
            String credentialJwt = new String(Base64.getDecoder().decode(credentialBase64), StandardCharsets.UTF_8);
            JWTClaimsSet claims = SignedJWT.parse(credentialJwt).getJWTClaimsSet();
            if (claims.getExpirationTime() != null) {
                return Optional.of(claims.getExpirationTime().toInstant());
            }
            Map<String, Object> vc = claims.getJSONObjectClaim("vc");
            Object validUntil = vc != null ? vc.getOrDefault("validUntil", vc.get("expirationDate")) : null;
            return validUntil != null ? Optional.of(Instant.parse(validUntil.toString())) : Optional.empty();
        } catch (IllegalArgumentException | ParseException | DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
