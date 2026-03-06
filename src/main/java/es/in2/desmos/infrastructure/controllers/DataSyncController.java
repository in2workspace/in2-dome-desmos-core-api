package es.in2.desmos.infrastructure.controllers;

import es.in2.desmos.application.workflows.jobs.P2PDataSyncJob;
import es.in2.desmos.domain.models.Entity;
import es.in2.desmos.domain.models.Id;
import es.in2.desmos.domain.models.MVEntity4DataNegotiation;
import es.in2.desmos.domain.utils.ApplicationUtils;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class DataSyncController {

    private final ApiConfig apiConfig;
    private final P2PDataSyncJob p2PDataSyncJob;

    @PostMapping(path = "/api/${api.version}/sync/p2p/discovery",
            consumes = "application/x-ndjson",
            produces = "application/x-ndjson")
    @ResponseStatus(HttpStatus.OK)
    public Flux<MVEntity4DataNegotiation> discoverySync(
            @RequestHeader("X-Issuer") @NotBlank String issuer,
            @RequestBody(required = false) Flux<MVEntity4DataNegotiation> discoverySyncRequest,
            ServerHttpResponse response) {

        String externalDomain = apiConfig.getExternalDomain();
        response.getHeaders().add("X-Issuer", externalDomain);

        String processId = ApplicationUtils.generateProcessId();

        return p2PDataSyncJob.dataDiscovery(processId, Mono.just(issuer), discoverySyncRequest)
                .doFirst(() -> {
                    log.info("ProcessID: {} - Starting Discovery Sync Controller", processId);
                    log.debug("ProcessID: {} - Starting Discovery Sync Controller from node: {}", processId, issuer);
                })
                .doOnComplete(() -> log.info("ProcessID: {} - Discovery Sync Controller completed successfully", processId))
                .doOnError(error -> log.error("ProcessID: {} - Error during Discovery Sync Controller", processId, error));

    }

    @PostMapping(value = "/api/${api.version}/sync/p2p/entities")
    @ResponseStatus(HttpStatus.OK)
    public Flux<Entity> entitiesSync(@RequestBody @Valid Mono<@NotNull Id[]> entitySyncRequest) {
        String processId = ApplicationUtils.generateProcessId();

        return entitySyncRequest
                .doFirst(() -> log.info("ProcessID: {} - Starting P2P Entities Synchronization Controller", processId))
                .doOnNext(ids -> log.info("ProcessID: {} - Processing P2P Entities Synchronization for {} entities", processId, ids.length))
                .map(List::of)
                .flatMapMany(ids -> {
                    log.debug("ProcessID: {} - Starting P2P Entities Synchronization. Ids: {}", processId, ids);
                    return p2PDataSyncJob.getLocalEntitiesByIdInBase64(processId, Mono.just(ids))
                            .flatMapMany(Flux::fromIterable);
                })
                .doOnComplete(() -> log.info("ProcessID: {} - P2P Entities Synchronization completed successfully", processId))
                .doOnError(error -> log.error("ProcessID: {} - Error occurred while processing the P2P Entities Synchronization Controller", processId, error));
    }

    @GetMapping("/api/${api.version}/entities/{id}")
    @ResponseStatus(HttpStatus.OK)
    public Flux<Entity> getEntities(@PathVariable @NotBlank String id) {
        String processId = ApplicationUtils.generateProcessId();

        return p2PDataSyncJob.getLocalEntitiesByIdInBase64(processId, Mono.just(List.of(new Id(id))))
                .doFirst(() -> log.debug("ProcessID: {} - Starting get entity with id: {}", processId, id))
                .flatMapMany(Flux::fromIterable)
                .doOnComplete(() -> log.debug("ProcessID: {} - Entity retrieval completed for id: {}", processId, id))
                .doOnError(error -> log.error("ProcessID: {} - Error fetching entity with id {}: {}", processId, id, error.getMessage()));
    }
}
