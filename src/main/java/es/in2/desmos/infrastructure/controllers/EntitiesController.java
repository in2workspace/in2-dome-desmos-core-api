package es.in2.desmos.infrastructure.controllers;

import es.in2.desmos.domain.models.Entity;
import es.in2.desmos.domain.models.Id;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import es.in2.desmos.domain.services.policies.PepWebClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/entities")
@RequiredArgsConstructor
public class EntitiesController {

    private final BrokerPublisherService brokerPublisherService;
    private final PepWebClient pepWebClient;

    @GetMapping("/{id}")
    @ResponseStatus(HttpStatus.OK)
    public Flux<Entity> getEntities(ServerHttpRequest request, @PathVariable String id) {
        String processId = UUID.randomUUID().toString();

        HttpHeaders headers = request.getHeaders();
        String path = request.getPath().toString();

        Mono<List<Id>> idsListMono = Mono.just(List.of(new Id(id)));

        //TODO Use pepwebclient response
        return pepWebClient.doRequest(headers, path)
                .onErrorResume(e -> {
                    log.debug("ProcessID: {} - Error connecting with PEP: {}", processId, e.getMessage());
                    return Mono.empty();
                })
                .thenMany(brokerPublisherService
                .findEntitiesAndItsSubentitiesByIdInBase64(processId, idsListMono, new ArrayList<>())
                .flatMapMany(Flux::fromIterable));
    }
}
