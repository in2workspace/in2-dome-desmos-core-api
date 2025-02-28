package es.in2.desmos.infrastructure.controllers;

import es.in2.desmos.domain.models.Entity;
import es.in2.desmos.domain.models.Id;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/entities")
@RequiredArgsConstructor
public class EntitiesController {

    private final BrokerPublisherService brokerPublisherService;

    @GetMapping("/{id}")
    @ResponseStatus(HttpStatus.OK)
    public Flux<Entity> getEntities(@PathVariable String id) {
        String processId = UUID.randomUUID().toString();

        Mono<List<Id>> idsListMono = Mono.just(List.of(new Id(id)));

        return brokerPublisherService
                .findEntitiesAndItsSubentitiesByIdInBase64(processId, idsListMono, new ArrayList<>())
                .flatMapMany(Flux::fromIterable);
    }
}
