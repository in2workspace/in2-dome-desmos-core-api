package es.in2.desmos.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.Payload;
import com.nimbusds.jwt.SignedJWT;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.infrastructure.configs.LearCredentialMachineConfig;
import es.in2.desmos.infrastructure.configs.VerifierConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class M2MAccessTokenProvider {
    private final JwtTokenProvider jwtTokenProvider;
    private final ApiConfig apiConfig;
    private final LearCredentialMachineConfig learCredentialMachineConfig;
    private final VerifierConfig verifierConfig;
    private final VerifierService verifierService;
    private final Environment environment;

    public Mono<String> getM2MAccessToken() {
        return Mono.fromCallable(this::getM2MFormUrlEncodeBodyValue)
                .flatMap(verifierService::performTokenRequest)
                .flatMap(tokenReponse -> Mono.just(tokenReponse.accessToken()));
    }

    private String getM2MFormUrlEncodeBodyValue() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put(OAuth2ParameterNames.GRANT_TYPE, learCredentialMachineConfig.getClientCredentialsGrantTypeValue());
        parameters.put(OAuth2ParameterNames.CLIENT_ID, apiConfig.getOrganizationId());
        parameters.put(OAuth2ParameterNames.CLIENT_ASSERTION_TYPE, learCredentialMachineConfig.getClientAssertionTypeValue());
        parameters.put(OAuth2ParameterNames.CLIENT_ASSERTION, createClientAssertion());

        return parameters.entrySet()
                .stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
    }


    private String createClientAssertion() {
        try {
            String vcMachineString = getVCinJWTDecodedFromBase64();
            SignedJWT vcMachineJWT = jwtTokenProvider.getSignedJWT(vcMachineString);

            Payload vcMachinePayload = vcMachineJWT.getPayload();
            String clientId = jwtTokenProvider.getClaimFromPayload(vcMachinePayload, "sub");

            Instant issueTime = Instant.now();
            long iat = issueTime.toEpochMilli();
            long exp = issueTime.plus(
                    Long.parseLong(learCredentialMachineConfig.getClientAssertionExpiration()),
                    ChronoUnit.valueOf(learCredentialMachineConfig.getClientAssertionExpirationUnitTime())
            ).toEpochMilli();

            String vpTokenJWTString = createVPTokenJWT(vcMachineString, clientId, iat, exp);

            // TODO: Remove check when Verifier will be updated
            String vpTokenJWTToPayload;
            if (Arrays.asList(environment.getActiveProfiles()).contains("dev")) {
                vpTokenJWTToPayload = Base64.getEncoder()
                        .encodeToString(vpTokenJWTString.getBytes(StandardCharsets.UTF_8));
            } else {
                vpTokenJWTToPayload = vpTokenJWTString;
            }

            Payload payload = new Payload(Map.of(
                    "aud", verifierConfig.getExternalUrl(),
                    "sub", clientId,
                    "vp_token", vpTokenJWTToPayload,
                    "iss", clientId,
                    "exp", exp,
                    "iat", iat,
                    "jti", UUID.randomUUID()
            ));

            return jwtTokenProvider.generateTokenWithPayload(payload.toString());
        } catch (ParseException | JOSEException e) {
            log.warn("Error parsing JWT", e);
            throw new JwtException("Error creating JWT for M2M");
        }
    }

    private String createVPTokenJWT(String vcMachineString, String clientId, long iat, long exp) {
        Map<String, Object> vp = createVP(vcMachineString, clientId);

        Payload payload = new Payload(Map.of(
                "sub", clientId,
                "iss", clientId,
                "nbf", iat,
                "iat", iat,
                "exp", exp,
                "jti", UUID.randomUUID(),
                "vp", vp
        ));

        try {
            return jwtTokenProvider.generateTokenWithPayload(payload.toString());
        } catch (JOSEException e) {
            log.warn("Error parsing JWT", e);
            throw new JwtException("Error creating JWT for M2M");
        }
    }

    private Map<String, Object> createVP(String vcMachineString, String clientId) {
        return Map.of(
                "@context", List.of("https://www.w3.org/2018/credentials/v1"),
                "holder", clientId,
                "id", "urn:uuid:" + UUID.randomUUID(),
                "type", List.of("VerifiablePresentation"),
                "verifiableCredential", List.of(vcMachineString)
        );
    }

    private String getVCinJWTDecodedFromBase64() {
        String vcTokenBase64 = learCredentialMachineConfig.getLearCredentialMachineInBase64();
        byte[] vcTokenDecoded = Base64.getDecoder().decode(vcTokenBase64.getBytes(StandardCharsets.UTF_8));
        return new String(vcTokenDecoded);
    }
}