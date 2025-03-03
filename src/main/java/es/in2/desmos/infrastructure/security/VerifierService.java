package es.in2.desmos.infrastructure.security;

import es.in2.desmos.domain.models.OpenIDProviderMetadata;
import es.in2.desmos.domain.models.VerifierOauth2AccessToken;
import reactor.core.publisher.Mono;

public interface VerifierService {
    Mono<Void> verifyToken(String accessToken);

    Mono<OpenIDProviderMetadata> getWellKnownInfo();

    Mono<VerifierOauth2AccessToken> performTokenRequest(String body);
}
