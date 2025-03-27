package es.in2.desmos.domain.services.policies;

import org.springframework.http.HttpMethod;
import reactor.core.publisher.Mono;

public interface PepWebClient {
    Mono<Void> doRequest(
            String originalUri,
            HttpMethod method,
            String remoteAddr,
            String originalHost,
            String authorization);
}
