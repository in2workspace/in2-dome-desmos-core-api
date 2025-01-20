package es.in2.desmos.domain.services.sync.impl;

import es.in2.desmos.domain.models.Entity;
import es.in2.desmos.domain.models.Id;
import es.in2.desmos.domain.services.sync.EntitySyncWebClient;
import es.in2.desmos.infrastructure.security.M2MAccessTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class EntitySyncWebClientImpl implements EntitySyncWebClient {
    private final WebClient webClient;
    private final M2MAccessTokenProvider m2MAccessTokenProvider;

    public Flux<String> makeRequest(String processId, Mono<String> issuerMono, Mono<Id[]> entitySyncRequest) {
        log.info("ProcessID: {} - Making a Entity Sync Web Client request", processId);

        return m2MAccessTokenProvider.getM2MAccessToken()
                .flatMapMany(accessToken ->
                        issuerMono.flatMapMany(issuer -> webClient
                        .post()
                        .uri(issuer + "/api/v1/sync/p2p/entities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(entitySyncRequest, Id[].class)
                        .retrieve()
                        .bodyToFlux(Entity.class)
                        .retryWhen(Retry.backoff(3, Duration.ofSeconds(2)))
                        .flatMap(x -> Flux.just(x.value()))));
    }
}