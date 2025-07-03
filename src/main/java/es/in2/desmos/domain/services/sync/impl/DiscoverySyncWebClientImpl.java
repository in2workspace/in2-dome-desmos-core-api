package es.in2.desmos.domain.services.sync.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.exceptions.DiscoverySyncException;
import es.in2.desmos.domain.models.MVEntity4DataNegotiation;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscoverySyncWebClientImpl implements DiscoverySyncWebClient {
    private final WebClient webClient;
    private final M2MAccessTokenProvider m2MAccessTokenProvider;

    @Override
    public Flux<MVEntity4DataNegotiation> makeRequest(String processId, Mono<String> externalAccessNodeMono,
                                                   Flux<MVEntity4DataNegotiation> externalMVEntities4DataNegotiation) {
        log.debug("ProcessID: {} - Making a Discovery Sync Web Client request", processId);

        // Sanitizar datos ANTES de enviarlos
        Flux<MVEntity4DataNegotiation> sanitizedFlux = externalMVEntities4DataNegotiation.map(entity -> {

            String safeVersion =
                    (entity.version() == null || entity.version().isBlank())
                            ? "v1"
                            : entity.version();

            String safeLastUpdate =
                    (entity.lastUpdate() == null || entity.lastUpdate().isBlank())
                            ? Instant.now().toString()
                            : entity.lastUpdate();

            String safeEndDateTime = (entity.endDateTime() == null || entity.endDateTime().isBlank())
                    ? "9999-12-31T23:59:59Z" // o alguna fecha por defecto válida
                    : entity.endDateTime();

            return new MVEntity4DataNegotiation(
                    entity.id(),
                    entity.type(),
                    safeVersion,
                    safeLastUpdate,
                    entity.lifecycleStatus(),
                    entity.startDateTime(),
                    safeEndDateTime,
                    entity.hash(),
                    entity.hashlink()
            );
        });
        // Paso 2 - imprimimos el JSON antes de enviarlo (solo para debug)
        sanitizedFlux
                .collectList()
                .doOnNext(list -> {
                    try {
                        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
                        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(list);
                        log.info("ProcessID: {} - JSON que se va a enviar:\n{}", processId, json);
                    } catch (Exception e) {
                        log.error("Error serializando JSON para logging", e);
                    }
                })
                .subscribe();

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
                                .header("X-Issuer", tuple.getT2())
                                .contentType(MediaType.APPLICATION_NDJSON)
                                .body(sanitizedFlux, MVEntity4DataNegotiation.class)
                                .retrieve()
                                .onStatus(status -> status != null && status.isSameCodeAs(HttpStatusCode.valueOf(200)),
                                        clientResponse -> {
                                            log.debug("ProcessID: {} - Discovery sync successfully", processId);
                                            return Mono.empty();
                                        })
                                .onStatus(status -> status != null && status.is4xxClientError(),
                                        clientResponse ->
                                                clientResponse.bodyToMono(String.class)
                                                        .flatMap(errorBody -> {
                                                            log.error("ProcessID: {} - Remote 4xx error: {}", processId, errorBody);
                                                            return Mono.error(new DiscoverySyncException(
                                                                    "Error 4xx occurred while discovery sync. Body: " + errorBody));
                                                        }))
                                .onStatus(status -> status != null && status.is5xxServerError(),
                                        clientResponse ->
                                                clientResponse.bodyToMono(String.class)
                                                        .flatMap(errorBody -> {
                                                            log.error("ProcessID: {} - Remote 5xx error: {}", processId, errorBody);
                                                            return Mono.error(new DiscoverySyncException(
                                                                    "Error 5xx occurred while discovery sync. Body: " + errorBody));
                                                        }))
                                .bodyToFlux(MVEntity4DataNegotiation.class)
                                .retry(3)
                );
    }
}