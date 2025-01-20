package es.in2.desmos.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.bc.BouncyCastleProviderSingleton;
import com.nimbusds.jose.jca.JCASupport;
import com.nimbusds.jwt.SignedJWT;
import es.in2.desmos.infrastructure.security.JwtTokenProvider;
import es.in2.desmos.infrastructure.security.SecurityProperties;
import es.in2.desmos.infrastructure.security.VerifierService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.spec.InvalidKeySpecException;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtTokenProviderTest {

    private final String resourceURI = "https://demos.dome-marketplace-lcl.org/api/v1/entities/12345678";
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private SecurityProperties securityProperties;

    @Mock
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
    void testGenerateToken() throws JOSEException {
        String token = jwtTokenProvider.generateToken(resourceURI);
        Assertions.assertNotNull(token);
    }

    @Test
    void testValidateSignedJwt() throws JOSEException {

        HashMap<String, String> publicKeysByUrl = new HashMap<>();
        publicKeysByUrl.put("origin", "0x045d016daba10ba4216c39c9d9f8aa0cae37f5acdbe14b3de78badfff0172f4ac2093896458ed17a28c559d7c915dfaf3d106e821c7415fecffc6c991f155a2c69");


        String jwtString = jwtTokenProvider.generateToken(resourceURI);
        System.out.println(jwtString);
        SignedJWT result = jwtTokenProvider.validateSignedJwt(jwtString,"origin", publicKeysByUrl).block();
        assert result != null;
        Assertions.assertEquals(jwtString, result.serialize());
    }

    @Test
    void testInvalidJwt() {
        String invalidJwt = "invalid.jwt.token";
        assertThrows(Exception.class, () -> jwtTokenProvider.validateSignedJwt(invalidJwt,"origin", new HashMap<>()).block());

    }

}
