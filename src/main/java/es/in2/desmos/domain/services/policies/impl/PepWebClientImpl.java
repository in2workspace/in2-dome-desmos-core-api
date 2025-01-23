package es.in2.desmos.domain.services.policies.impl;

import es.in2.desmos.domain.services.policies.PepWebClient;
import es.in2.desmos.infrastructure.configs.PepConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class PepWebClientImpl implements PepWebClient {
    private final WebClient webClient;
    private final PepConfig pepConfig;

    @Override
    public Mono<Void>
    doRequest(
            String originalUri,
            HttpMethod originalMethod,
            String originalRemoteAddr,
            String originalHost,
            String authorization) {
        String uri = pepConfig.getUrl();
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Original-URI", originalUri);
        headers.add("X-Original-Method", originalMethod.name());
        headers.add("X-Original-Remote-Addr", originalRemoteAddr);
        headers.add("X-Original-Host", originalHost);
        headers.add("Authorization", authorization);

        return webClient
                .get()
                .uri(uri)
                .headers(x -> x.addAll(headers))
                .retrieve()
                .bodyToMono(Void.class);
    }
}
