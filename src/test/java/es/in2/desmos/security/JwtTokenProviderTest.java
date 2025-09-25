package es.in2.desmos.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.bc.BouncyCastleProviderSingleton;
import com.nimbusds.jose.jca.JCASupport;
import com.nimbusds.jwt.SignedJWT;
import es.in2.desmos.domain.exceptions.JWTClaimMissingException;
import es.in2.desmos.infrastructure.configs.properties.SecurityProperties;
import es.in2.desmos.infrastructure.security.JwtTokenProvider;
import es.in2.desmos.infrastructure.security.VerifierService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.JwtException;
import reactor.core.publisher.Mono;

import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.spec.InvalidKeySpecException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private SecurityProperties securityProperties;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    VerifierService verifierService;

    @BeforeEach
    void setUp() throws InvalidKeySpecException, NoSuchAlgorithmException, NoSuchProviderException {
        MockitoAnnotations.openMocks(this);
        when(securityProperties.privateKey())
                .thenReturn("0xd1d346bbb4e3748b370c5985face9a4e5b402dcf41d3f715a455d08144b2327f");
        jwtTokenProvider = new JwtTokenProvider(securityProperties, objectMapper, verifierService);
    }

    @Test
    void testSecurityProvider() {
        Provider bc = BouncyCastleProviderSingleton.getInstance();
        Assertions.assertTrue(JCASupport.isSupported(JWSAlgorithm.ES256, bc));
    }

    @Test
    void testGenerateTokenWithPayload() throws JOSEException {
        String payload = """
                 {
                   "sub": "1234567890",
                   "name": "John Doe",
                   "iat": 1516239022
                 }
                 """;

        String token = jwtTokenProvider.generateTokenWithPayload(payload);
        Assertions.assertNotNull(token);
    }

    @Test
    void testGenerateTokenWithPayloadThrowJwtException() throws JsonProcessingException {
        String payload = """
                 {
                   "sub": "1234567890",
                   "name": "John Doe",
                   "iat": 1516239022
                 }
                 """;

        when(objectMapper.readTree(anyString())).thenThrow(JsonProcessingException.class);


        assertThrows(JwtException.class, () -> jwtTokenProvider.generateTokenWithPayload(payload));
    }

    @Test
    void testValidateM2MJwt() {
        String jwtString = "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyLCJleHAiOjMyNDczOTk4NTY0LCJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6Ijg3NWU4YTQyZjJhNTFlNTVkMGNhN2MwMDg4ZjZjZTU1In0.eyJuYW1lIjoiSm9obiBEb2UiLCJpYXQiOjE1MTYyMzkwMjIsImV4cCI6MzI0NzM5OTg1NjR9.ARzDuqn_wjRSrdsMeT-oSEm9GMD5u6oh8iouNjKwHvcDQbfgLveYU9Y9TxxiZ2d4Sh_AGRE4JSikUrPbuiX55g";
        System.out.println(jwtString);

        when(verifierService.verifyToken(anyString())).thenReturn(Mono.empty());

        SignedJWT result = jwtTokenProvider.validateSignedJwt(jwtString).block();
        assert result != null;
        Assertions.assertEquals(jwtString, result.serialize());
    }

    @Test

    void testInvalidJwt() {
        String invalidJwt = "invalid.jwt.token";
        assertThrows(Exception.class, () -> jwtTokenProvider.validateSignedJwt(invalidJwt).block());
    }

    @Test
    void getClaimFromPayload_validClaim_returnsClaimValue() {
        Payload payload = new Payload("{\"username\":\"testUser\",\"role\":\"admin\"}");

        String claimValue = jwtTokenProvider.getClaimFromPayload(payload, "username");

        assertThat(claimValue).isEqualTo("testUser");
    }

    @Test
    void getClaimFromPayload_missingClaim_throwsJWTClaimMissingException() {
        Payload payload = new Payload("{\"username\":\"testUser\"}");

        assertThatThrownBy(() -> jwtTokenProvider.getClaimFromPayload(payload, "role"))
                .isInstanceOf(JWTClaimMissingException.class)
                .hasMessage("The 'role' claim is missing or empty in the JWT payload.");
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void getClaimFromPayload_nullPayload_throwsNullPointerException() {
        assertThatThrownBy(() -> jwtTokenProvider.getClaimFromPayload(null, "username"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void getClaimFromPayload_emptyClaimName_throwsJWTClaimMissingException() {
        Payload payload = new Payload("{\"username\":\"testUser\"}");

        assertThatThrownBy(() -> jwtTokenProvider.getClaimFromPayload(payload, ""))
                .isInstanceOf(JWTClaimMissingException.class)
                .hasMessage("The '' claim is missing or empty in the JWT payload.");
    }

}
