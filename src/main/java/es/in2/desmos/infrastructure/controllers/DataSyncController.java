package es.in2.desmos.infrastructure.controllers;

import es.in2.desmos.application.workflows.jobs.P2PDataSyncJob;
import es.in2.desmos.domain.models.*;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
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

    @PostMapping("/api/v1/sync/p2p/discovery")
    @ResponseStatus(HttpStatus.OK)
    public Mono<DiscoverySyncResponse> discoverySync(@RequestBody @Valid Mono<DiscoverySyncRequest> discoverySyncRequest) {
        String processId = UUID.randomUUID().toString();
        log.info("ProcessID: {} - Starting P2P Data Synchronization Discovery Controller", processId);
        return discoverySyncRequest.flatMap(request -> {
                    log.debug("ProcessID: {} - Starting P2P Data Synchronization Discovery: {}", processId, request);
                    Mono<List<MVEntity4DataNegotiation>> externalMvEntities4DataNegotiationMono = request.externalMVEntities4DataNegotiation().collectList();
                    Mono<String> issuerMono = request.issuer();
                    return p2PDataSyncJob.dataDiscovery(processId, issuerMono, externalMvEntities4DataNegotiationMono)
                            .flatMap(localMvEntities4DataNegotiation -> {
                                Mono<List<MVEntity4DataNegotiation>> localMvEntities4DataNegotiationMono = Mono.just(localMvEntities4DataNegotiation);

                                return localMvEntities4DataNegotiationMono.map(mvEntities4DataNegotiation ->
                                        new DiscoverySyncResponse(Mono.just(apiConfig.getExternalDomain()), Flux.fromIterable(mvEntities4DataNegotiation)));
                            });
                })
                .doOnSuccess(success -> log.info("ProcessID: {} - P2P Data Synchronization Discovery successfully.", processId))
                .doOnError(error -> log.error("ProcessID: {} - Error occurred while processing the P2P Data Synchronization Discovery Controller: {}", processId, error.getMessage()));
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
