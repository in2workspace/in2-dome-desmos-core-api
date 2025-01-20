package es.in2.desmos.domain.services.sync.impl;

import es.in2.desmos.domain.models.DiscoverySyncRequest;
import es.in2.desmos.domain.models.DiscoverySyncResponse;
import es.in2.desmos.domain.services.sync.DiscoverySyncWebClient;
import es.in2.desmos.infrastructure.security.M2MAccessTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
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
                                .uri(tuple.getT1() + "/api/v1/sync/p2p/discovery")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tuple.getT2())
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(discoverySyncRequest, DiscoverySyncRequest.class)
                                .retrieve()
                                .bodyToMono(DiscoverySyncResponse.class)
                                .retry(3));
    }
}
