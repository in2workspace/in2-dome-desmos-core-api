package es.in2.desmos.domain.services.policies.impl;

import es.in2.desmos.domain.services.policies.PepWebClient;
import es.in2.desmos.infrastructure.configs.PepConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class PepWebClientImpl implements PepWebClient {
    private final WebClient webClient;
    private final PepConfig pepConfig;

    @Override
    public Mono<Void>
    doRequest(HttpHeaders headers, String path) {
        String uri = pepConfig.getExternalDomain() + path;

        return webClient
                .get()
                .uri(uri)
                .headers(x -> x.addAll(headers))
                .retrieve()
                .bodyToMono(Void.class)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2)));
    }
}
