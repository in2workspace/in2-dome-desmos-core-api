package es.in2.desmos.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.Payload;
import com.nimbusds.jwt.SignedJWT;
import es.in2.desmos.domain.models.VerifierOauth2AccessToken;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.infrastructure.configs.LearCredentialMachineConfig;
import es.in2.desmos.infrastructure.configs.VerifierConfig;
import es.in2.desmos.it.ContainerManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.text.ParseException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class M2MAccessTokenProviderTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private ApiConfig apiConfig;

    @Mock
    private LearCredentialMachineConfig learCredentialMachineConfig;

    @Mock
    private VerifierConfig verifierConfig;

    @Mock
    private VerifierService verifierService;

    @Mock
    private SignedJWT signedJWT;

    @InjectMocks
    private M2MAccessTokenProvider m2mAccessTokenProvider;

    @DynamicPropertySource
    private static void setDynamicProperties(DynamicPropertyRegistry registry) {
        ContainerManager.postgresqlProperties(registry);
    }

    @BeforeEach
    void setUp() {
        when(apiConfig.getOrganizationId()).thenReturn("test-client-id");
        when(learCredentialMachineConfig.getClientCredentialsGrantTypeValue()).thenReturn("client_credentials");
        when(learCredentialMachineConfig.getClientAssertionTypeValue()).thenReturn("urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        when(learCredentialMachineConfig.getLearCredentialMachineInBase64()).thenReturn("ZXlKaGJHY2lPaUpJVXpJMU5pSXNJblI1Y0NJNklrcFhWQ0o5LmV5SnpkV0lpT2lJeE1qTTBOVFkzT0Rrd0lpd2libUZ0WlNJNklrcHZhRzRnUkc5bElpd2lhV0YwSWpveE5URTJNak01TURJeWZRLlNmbEt4d1JKU01lS0tGMlFUNGZ3cE1lSmYzNlBPazZ5SlZfYWRRc3N3NWM=");
    }

    @Test
    void itShouldReturnAccessToken() throws ParseException, JOSEException {
        String expectedAccessToken = "mocked-access-token";

        when(verifierService.performTokenRequest(any())).thenReturn(Mono.just(new VerifierOauth2AccessToken("", "", "")));
        when(signedJWT.getPayload()).thenReturn(new Payload(Map.of("sub", "test-client-id")));
        when(jwtTokenProvider.getClaimFromPayload(any(), any())).thenReturn("test-client-id");
        when(jwtTokenProvider.getSignedJWT(any())).thenReturn(signedJWT);
        when(jwtTokenProvider.generateTokenWithPayload(any())).thenReturn("mocked-client-assertion");
        when(verifierService.performTokenRequest(any())).thenReturn(Mono.just(new VerifierOauth2AccessToken(expectedAccessToken, "", "")));
        when(learCredentialMachineConfig.getClientAssertionExpiration()).thenReturn("5");
        when(learCredentialMachineConfig.getClientAssertionExpirationUnitTime()).thenReturn("MINUTES");
        when(verifierConfig.getExternalDomain()).thenReturn("https://test-verifier.com");

        StepVerifier.create(m2mAccessTokenProvider.getM2MAccessToken())
                .assertNext(accessToken -> assertThat(accessToken).isEqualTo(expectedAccessToken))
                .verifyComplete();
    }

    @Test
    void itShouldHandleErrorWhenGetSignedJwtThrowParseException() throws ParseException {
        when(jwtTokenProvider.getSignedJWT(any())).thenThrow(ParseException.class);

        StepVerifier.create(m2mAccessTokenProvider.getM2MAccessToken())
                .expectErrorMatches(JwtException.class::isInstance)
                .verify();
    }

    @Test
    void itShouldHandleErrorWhenGetSignedJwtThrowJOSEExceptionException() throws ParseException, JOSEException {
        when(jwtTokenProvider.getSignedJWT(any())).thenReturn(signedJWT);
        when(learCredentialMachineConfig.getClientAssertionExpiration()).thenReturn("5");
        when(learCredentialMachineConfig.getClientAssertionExpirationUnitTime()).thenReturn("MINUTES");
        when(jwtTokenProvider.getClaimFromPayload(any(), any())).thenReturn("test-client-id");
        when(jwtTokenProvider.generateTokenWithPayload(any())).thenThrow(JOSEException.class);

        StepVerifier.create(m2mAccessTokenProvider.getM2MAccessToken())
                .expectErrorMatches(JwtException.class::isInstance)
                .verify();
    }
}