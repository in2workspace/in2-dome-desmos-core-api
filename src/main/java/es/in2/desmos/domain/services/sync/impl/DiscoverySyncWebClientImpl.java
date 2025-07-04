package es.in2.desmos.domain.services.sync.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.exceptions.DiscoverySyncException;
import es.in2.desmos.domain.models.MVEntity4DataNegotiation;
import es.in2.desmos.domain.services.sync.DiscoverySyncWebClient;
import es.in2.desmos.domain.utils.EndpointsConstants;
import es.in2.desmos.infrastructure.security.M2MAccessTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscoverySyncWebClientImpl implements DiscoverySyncWebClient {
    private final WebClient webClient;
    private final M2MAccessTokenProvider m2MAccessTokenProvider;

    @Override
    public Flux<MVEntity4DataNegotiation> makeRequest(
            String processId,
            Mono<String> externalAccessNodeMono,
            String externalDomain,
            Flux<MVEntity4DataNegotiation> externalMVEntities4DataNegotiation) {

        log.debug("ProcessID: {} - Making a Discovery Sync Web Client request for domain: {}", processId, externalDomain);

        return externalAccessNodeMono
                .zipWith(m2MAccessTokenProvider.getM2MAccessToken())
                .flatMapMany(tuple ->
                        webClient
                                .post()
                                .uri(UriComponentsBuilder.fromHttpUrl(tuple.getT1())
                                        .path(EndpointsConstants.P2P_DISCOVERY_SYNC)
                                        .build()
                                        .toUriString())
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tuple.getT2())
                                .header("X-Issuer", externalDomain)
                                .contentType(MediaType.valueOf("application/x-ndjson"))
                                .accept(MediaType.valueOf("application/x-ndjson"))
                                .body(externalMVEntities4DataNegotiation, MVEntity4DataNegotiation.class)
                                .retrieve()
                                .onStatus(
                                        status -> !status.is2xxSuccessful(),
                                        clientResponse ->
                                                clientResponse.bodyToMono(String.class)
                                                        .flatMap(errorBody -> {
                                                            if (clientResponse.statusCode().is4xxClientError()) {
                                                                log.error("ProcessID: {} - Remote 4xx error: {}", processId, errorBody);
                                                                return Mono.error(new DiscoverySyncException(
                                                                        String.format("Error 4xx occurred while discovery sync. ProcessId: %s | X-Issuer: %s | Body: %s",
                                                                                processId, externalDomain, errorBody)));
                                                            } else if (clientResponse.statusCode().is5xxServerError()) {
                                                                log.error("ProcessID: {} - Remote 5xx error: {}", processId, errorBody);
                                                                return Mono.error(new DiscoverySyncException(
                                                                        "Error 5xx occurred while discovery sync. Body: " + errorBody));
                                                            } else {
                                                                log.error("ProcessID: {} - Unexpected HTTP error: {} Body: {}", processId,
                                                                        clientResponse.statusCode(), errorBody);
                                                                return Mono.error(new DiscoverySyncException(
                                                                        String.format("Unexpected HTTP error during discovery sync. Status: %s | Body: %s",
                                                                                clientResponse.statusCode(), errorBody)));
                                                            }
                                                        })
                                )
                                .bodyToFlux(MVEntity4DataNegotiation.class)
                                .doOnNext(entity ->
                                        log.debug("ProcessID: {} - Discovery Sync Web Client request successfully", processId)
                                )
                                .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(1))
                                        .filter(throwable -> !(throwable instanceof DiscoverySyncException)))
                );
    }
}
