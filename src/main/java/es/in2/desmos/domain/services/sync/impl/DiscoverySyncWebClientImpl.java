package es.in2.desmos.domain.services.sync.impl;

import es.in2.desmos.domain.exceptions.DiscoverySyncException;
import es.in2.desmos.domain.models.DiscoverySyncRequest;
import es.in2.desmos.domain.models.DiscoverySyncResponse;
import es.in2.desmos.domain.services.sync.DiscoverySyncWebClient;
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
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscoverySyncWebClientImpl implements DiscoverySyncWebClient {
    private final WebClient webClient;
    private final M2MAccessTokenProvider m2MAccessTokenProvider;

    @Override
    public Mono<DiscoverySyncResponse> makeRequest(String processId, Mono<String> externalAccessNodeMono, Mono<DiscoverySyncRequest> discoverySyncRequest) {
        log.debug("ProcessID: {} - Making a Discovery Sync Web Client request", processId);
        return externalAccessNodeMono
                .zipWith(m2MAccessTokenProvider.getM2MAccessToken())
                .flatMap(tuple ->
                        webClient
                                .post()
                                .uri(UriComponentsBuilder.fromHttpUrl(tuple.getT1())
                                        .path(EndpointsConstants.P2P_DISCOVERY_SYNC)
                                        .build()
                                        .toUriString())
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tuple.getT2())
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(discoverySyncRequest, DiscoverySyncRequest.class)
                                .retrieve()
                                .onStatus(status -> status != null && status.isSameCodeAs(HttpStatusCode.valueOf(200)),
                                        clientResponse -> {
                                            log.debug("ProcessID: {} - Discovery sync successfully", processId);
                                            return Mono.empty();
                                        })
                                .onStatus(status -> status != null && status.is4xxClientError(),
                                        clientResponse ->
                                            Mono.error(new DiscoverySyncException("Error occurred while discovery sync")))
                                .onStatus(status -> status != null && status.is5xxServerError(),
                                        clientResponse ->
                                                Mono.error(new DiscoverySyncException(
                                                        "Error occurred while discovery sync")))
                                .bodyToMono(DiscoverySyncResponse.class)
                                .retry(3));
    }
}
