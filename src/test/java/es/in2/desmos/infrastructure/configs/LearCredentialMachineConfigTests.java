package es.in2.desmos.infrastructure.configs;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import es.in2.desmos.infrastructure.configs.properties.SecurityProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LearCredentialMachineConfigTests {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void itShouldReadExpirationFromExpClaim() throws JOSEException {
        Instant expiration = Instant.parse("2027-01-31T00:00:00Z");
        String credential = credentialInBase64(new JWTClaimsSet.Builder().expirationTime(Date.from(expiration)).build());

        assertThat(configFor(credential).getLearCredentialMachineExpiration()).contains(expiration);
    }

    @Test
    void itShouldFallBackToCredentialValidUntil() throws JOSEException {
        String credential = credentialInBase64(new JWTClaimsSet.Builder()
                .claim("vc", Map.of("validUntil", "2027-06-30T12:00:00Z"))
                .build());

        assertThat(configFor(credential).getLearCredentialMachineExpiration()).contains(Instant.parse("2027-06-30T12:00:00Z"));
    }

    @Test
    void itShouldReturnEmptyWhenCredentialIsMissingOrUnreadable() {
        assertThat(configFor(null).getLearCredentialMachineExpiration()).isEmpty();
        assertThat(configFor("not-base64!").getLearCredentialMachineExpiration()).isEmpty();
        assertThat(configFor(Base64.getEncoder().encodeToString("not-a-jwt".getBytes(StandardCharsets.UTF_8)))
                .getLearCredentialMachineExpiration()).isEmpty();
    }

    private static LearCredentialMachineConfig configFor(String credentialInBase64) {
        return new LearCredentialMachineConfig(new SecurityProperties("private-key", credentialInBase64));
    }

    private static String credentialInBase64(JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET));
        return Base64.getEncoder().encodeToString(jwt.serialize().getBytes(StandardCharsets.UTF_8));
    }
}
