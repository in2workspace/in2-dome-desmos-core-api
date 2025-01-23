package es.in2.desmos.domain.services.policies;

import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

public interface PepWebClient {
    Mono<Void> doRequest(HttpHeaders headers, String path);
}
