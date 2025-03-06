package es.in2.desmos.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import es.in2.desmos.domain.exceptions.JWTVerificationException;
import es.in2.desmos.domain.exceptions.PerformTokenRequestException;
import es.in2.desmos.domain.exceptions.TokenFetchException;
import es.in2.desmos.domain.exceptions.WellKnownInfoFetchException;
import es.in2.desmos.domain.models.OpenIDProviderMetadata;
import es.in2.desmos.domain.models.OIDCAccessTokenResponse;
import es.in2.desmos.infrastructure.configs.VerifierConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.text.ParseException;
import java.util.Date;

@Service
@Slf4j
@RequiredArgsConstructor
public class VerifierServiceImpl implements VerifierService {

    private final WebClient oauth2VerifierWebClient;
    private final VerifierConfig verifierConfig;

    // Cache to store JWKs and avoid multiple endpoint calls
    private JWKSet cachedJWKSet;
    private final Object jwkLock = new Object();

    @Override
    public Mono<Void> verifyToken(String accessToken) {
        return parseAndValidateJwt(accessToken)
                .doOnSuccess(unused -> log.info("VerifyToken -- IS VALID"))
                .onErrorResume(e -> {
                    log.error("Error while verifying token", e);
                    return Mono.error(e);
                });
    }

    private Mono<Void> parseAndValidateJwt(String accessToken) {
        return getWellKnownInfo()
                .flatMap(metadata -> fetchJWKSet(metadata.jwksUri()))
                .flatMap(jwkSet -> {
                    try {
                        SignedJWT signedJWT = SignedJWT.parse(accessToken);
                        JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

                        // Validate the issuer
                        if (!verifierConfig.getExternalUrl().equals(claims.getIssuer())) {
                            return Mono.error(new JWTVerificationException("Invalid issuer"));
                        }

                        // Validate expiration time
                        if (claims.getExpirationTime() == null || new Date().after(claims.getExpirationTime())) {
                            return Mono.error(new JWTVerificationException("Token has expired"));
                        }

                        // Verify the signature
                        JWSVerifier verifier = getJWSVerifier(signedJWT, jwkSet);
                        if (!signedJWT.verify(verifier)) {
                            return Mono.error(new JWTVerificationException("Invalid token signature"));
                        }

                        return Mono.empty(); // Valid token
                    } catch (ParseException | JOSEException e) {
                        log.error("Error parsing or verifying JWT", e);
                        return Mono.error(new JWTVerificationException("Error parsing or verifying JWT"));
                    }
                });
    }

    private JWSVerifier getJWSVerifier(SignedJWT signedJWT, JWKSet jwkSet) throws JOSEException {
        String keyId = signedJWT.getHeader().getKeyID();
        JWK jwk = jwkSet.getKeyByKeyId(keyId);
        if (jwk == null) {
            throw new JOSEException("No matching JWK found for Key ID: " + keyId);
        }

        // Create the appropriate verifier based on the key type
        return switch (jwk.getKeyType().toString()) {
            case "RSA" -> new RSASSAVerifier(((RSAKey) jwk).toRSAPublicKey());
            case "EC" -> new ECDSAVerifier(((ECKey) jwk).toECPublicKey());
            case "oct" -> new MACVerifier(((OctetSequenceKey) jwk).toByteArray());
            default -> throw new JOSEException("Unsupported JWK type: " + jwk.getKeyType());
        };
    }

    private Mono<JWKSet> fetchJWKSet(String jwksUri) {
        if (cachedJWKSet != null) {
            return Mono.just(cachedJWKSet);
        }

        return Mono.defer(() -> {
            synchronized (jwkLock) {
                if (cachedJWKSet != null) {
                    return Mono.just(cachedJWKSet);
                }
                return oauth2VerifierWebClient.get()
                        .uri(jwksUri)
                        .retrieve()
                        .bodyToMono(String.class)
                        .<JWKSet>handle((jwks, sink) -> {
                            try {
                                cachedJWKSet = JWKSet.parse(jwks);
                                sink.next(cachedJWKSet);
                            } catch (ParseException e) {
                                sink.error(new JWTVerificationException("Error parsing the JWK Set"));
                            }
                        })
                        .onErrorMap(e -> new JWTVerificationException("Error fetching the JWK Set"));
            }
        });
    }

    @Override
    public Mono<OpenIDProviderMetadata> getWellKnownInfo() {
        String wellKnownInfoEndpoint = verifierConfig.getExternalUrl() + verifierConfig.getWellKnownPath();

        return oauth2VerifierWebClient.get()
                .uri(wellKnownInfoEndpoint)
                .retrieve()
                .bodyToMono(OpenIDProviderMetadata.class)
                .onErrorMap(e -> new WellKnownInfoFetchException("Error fetching OpenID Provider Metadata", e));
    }

    @Override
    public Mono<OIDCAccessTokenResponse> performTokenRequest(String body) {
        return getWellKnownInfo()
                .flatMap(metadata ->
                        oauth2VerifierWebClient
                                .post()
                                .uri(metadata.tokenEndpoint())
                                .header(
                                        verifierConfig.getWellKnownContentType(),
                                        verifierConfig.getWellKnownContentTypeUrlEncodedForm())
                                .bodyValue(body)
                                .retrieve()
                                .onStatus(
                                        HttpStatusCode::isError,
                                        response -> response.bodyToMono(String.class)
                                                .flatMap(errorBody ->
                                                        Mono.error(new PerformTokenRequestException(
                                                                "Error fetching the token: " + errorBody))))
                                .bodyToMono(OIDCAccessTokenResponse.class)
                                .onErrorMap(e -> new TokenFetchException("Error fetching the token", e)));
    }
}

