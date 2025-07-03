package es.in2.desmos.infrastructure.controllers;

import es.in2.desmos.application.workflows.jobs.P2PDataSyncJob;
import es.in2.desmos.domain.models.Entity;
import es.in2.desmos.domain.models.Id;
import es.in2.desmos.domain.models.MVEntity4DataNegotiation;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class DataSyncController {

    private final ApiConfig apiConfig;
    private final P2PDataSyncJob p2PDataSyncJob;
    private final BrokerPublisherService brokerPublisherService;

    @PostMapping(path = "/api/v1/sync/p2p/discovery")
    @ResponseStatus(HttpStatus.OK)
    public Flux<MVEntity4DataNegotiation> discoverySync(
            @RequestHeader("X-Issuer") @NotBlank String issuer,
            @RequestBody Flux<MVEntity4DataNegotiation> discoverySyncRequest,
            ServerHttpResponse response) {

        String processId = UUID.randomUUID().toString();
        response.getHeaders().add("X-Issuer", apiConfig.getExternalDomain());
        Mono<String> issuerMono = Mono.just(issuer);
        log.info("ProcessID: {} - Starting P2P Data Synchronization Discovery Controller", processId);
        return p2PDataSyncJob.dataDiscovery(processId, issuerMono, discoverySyncRequest)
                .doOnComplete(() -> log.info("ProcessID: {} - Discovery completed successfully", processId))
                .doOnError(error -> log.error("ProcessID: {} - Error during discovery: {}", processId, error.getMessage()));

    }

    @PostMapping(value = "/api/v1/sync/p2p/entities")
    @ResponseStatus(HttpStatus.OK)
    public Flux<Entity> entitiesSync(@RequestBody @Valid Mono<@NotNull Id[]> entitySyncRequest) {
        String processId = UUID.randomUUID().toString();
        log.info("ProcessID: {} - Starting P2P Entities Synchronization Controller", processId);

        return entitySyncRequest.flatMapMany(Flux::fromArray)
                .collectList()
                .flatMapMany(ids -> {
                    log.debug("ProcessID: {} - Starting P2P Entities Synchronization: {}", processId, ids);

                    Mono<List<Id>> idsMono = Mono.just(ids);
                    return p2PDataSyncJob.getLocalEntitiesByIdInBase64(processId, idsMono)
                            .flatMapMany(Flux::fromIterable);
                })
                .doOnComplete(() -> log.info("ProcessID: {} - P2P Entities Synchronization successfully.", processId))
                .doOnError(error -> log.error("ProcessID: {} - Error occurred while processing the P2P Entities Synchronization Controller: {}", processId, error.getMessage()));
    }

    @GetMapping("/api/v1/entities/{id}")
    @ResponseStatus(HttpStatus.OK)
    public Flux<Entity> getEntities(@PathVariable String id) {
        String processId = UUID.randomUUID().toString();
        Mono<List<Id>> idsListMono = Mono.just(List.of(new Id(id)));
        return brokerPublisherService
                .findEntitiesAndItsSubentitiesByIdInBase64(processId, idsListMono, new ArrayList<>())
                .flatMapMany(Flux::fromIterable);
    }

}
