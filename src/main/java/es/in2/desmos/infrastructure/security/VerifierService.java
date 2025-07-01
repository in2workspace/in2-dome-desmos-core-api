package es.in2.desmos.infrastructure.security;

import es.in2.desmos.domain.models.OIDCAccessTokenResponse;
import es.in2.desmos.domain.models.OpenIDProviderMetadata;
import reactor.core.publisher.Mono;

public interface VerifierService {
    Mono<Void> verifyToken(String accessToken);

    Mono<OpenIDProviderMetadata> getWellKnownInfo();

    Mono<OIDCAccessTokenResponse> performTokenRequest(String body);
}
