package es.in2.desmos.domain.services.sync.impl;

import es.in2.desmos.domain.exceptions.EntitySyncException;
import es.in2.desmos.domain.models.Entity;
import es.in2.desmos.domain.models.Id;
import es.in2.desmos.domain.services.sync.EntitySyncWebClient;
import es.in2.desmos.domain.utils.EndpointsConstants;
import es.in2.desmos.infrastructure.security.M2MAccessTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
                                .uri(UriComponentsBuilder.fromHttpUrl(issuer)
                                        .path(EndpointsConstants.P2P_ENTITIES_SYNC)
                                        .build()
                                        .toUriString())
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(entitySyncRequest, Id[].class)
                                .retrieve()
                                .onStatus(status -> status != null && status.isSameCodeAs(HttpStatusCode.valueOf(200)),
                                        clientResponse -> {
                                            log.debug("ProcessID: {} - Entity sync successfully", processId);
                                            return Mono.empty();
                                        })
                                .onStatus(status -> status != null && status.is4xxClientError(),
                                        clientResponse ->
                                            Mono.error(new EntitySyncException("Error occurred while entity sync")))
                                .onStatus(status -> status != null && status.is5xxServerError(),
                                        clientResponse ->
                                                Mono.error(new EntitySyncException(
                                                        "Error occurred while entity sync")))
                                .bodyToFlux(Entity.class)
                                .retry(3)
                                .map(Entity::value)));
    }
}