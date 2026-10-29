package es.in2.desmos.application.workflows.jobs.impl;

import es.in2.desmos.application.workflows.jobs.DataPublicationJob;
import es.in2.desmos.domain.models.*;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import es.in2.desmos.domain.utils.ExpectedFailures;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataPublicationJobImpl implements DataPublicationJob {
    // Writing every received entity at once exhausts Scorpio's database connection pool on large synchronizations
    private static final int BROKER_WRITE_CONCURRENCY = 8;

    private final AuditRecordService auditRecordService;
    private final BrokerPublisherService brokerPublisherService;

    public Mono<Void> verifyData(String processId, Mono<String> issuer, Mono<Map<Id, Entity>> entitiesByIdMono, Mono<List<MVEntity4DataNegotiation>> allMVEntity4DataNegotiation, Mono<Map<Id, HashAndHashLink>> existingEntitiesOriginalValidationDataById) {
        log.info("ProcessID: {} - Starting Data Verification Job", processId);

        return buildAndSaveAuditRecordFromDataSync(processId, issuer, entitiesByIdMono, allMVEntity4DataNegotiation, AuditRecordStatus.RETRIEVED)
                .then(createEntitiesToContextBroker(processId, entitiesByIdMono))
                .flatMap(publishedEntitiesById -> buildAndSaveAuditRecordFromDataSync(processId, issuer, Mono.just(publishedEntitiesById), allMVEntity4DataNegotiation, AuditRecordStatus.PUBLISHED));
    }

    private Mono<Void> buildAndSaveAuditRecordFromDataSync(String processId, Mono<String> issuerMono, Mono<Map<Id, Entity>> rcvdEntitiesByIdMono, Mono<List<MVEntity4DataNegotiation>> mvEntity4DataNegotiationListMono, AuditRecordStatus auditRecordStatus) {
        return Mono.zip(rcvdEntitiesByIdMono, mvEntity4DataNegotiationListMono)
                .flatMapMany(tuple -> {
                    Map<Id, Entity> rcvdEntitiesById = tuple.getT1();
                    List<MVEntity4DataNegotiation> mvEntity4DataNegotiationList = tuple.getT2();

                    return Flux.fromIterable(mvEntity4DataNegotiationList)
                            .filter(entity4DataNegotiation -> rcvdEntitiesById.containsKey(new Id(entity4DataNegotiation.id())))
                            .concatMap(entity4DataNegotiation -> issuerMono
                                    .flatMap(issuer -> {
                                        MVAuditServiceEntity4DataNegotiation mvAuditServiceEntity4DataNegotiation = new MVAuditServiceEntity4DataNegotiation(entity4DataNegotiation.id(), entity4DataNegotiation.type(), entity4DataNegotiation.hash(), entity4DataNegotiation.hashlink());
                                        return auditRecordService.buildAndSaveAuditRecordFromDataSync(processId, issuer, mvAuditServiceEntity4DataNegotiation, auditRecordStatus);
                                    }));
                })
                .collectList()
                .then();
    }

    // Each entity is written on its own: a broker rejection only drops that entity instead of cancelling the
    // writes still in flight, and only the written entities are returned to be audited as published.
    private Mono<Map<Id, Entity>> createEntitiesToContextBroker(String processId, Mono<Map<Id, Entity>> entitiesByIdMono) {
        return entitiesByIdMono
                .flatMap(entitiesById -> Flux.fromIterable(entitiesById.entrySet())
                        .flatMap(entry -> brokerPublisherService.publishDataToBroker(processId, entry.getKey().id(), entry.getValue().value())
                                        .thenReturn(entry)
                                        .onErrorResume(e -> {
                                            logEntityNotWritten(processId, entry, e);
                                            return Mono.empty();
                                        }),
                                BROKER_WRITE_CONCURRENCY)
                        .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                        .doOnNext(writtenEntitiesById -> logEntitiesNotWritten(processId, entitiesById, writtenEntitiesById)));
    }

    private void logEntityNotWritten(String processId, Map.Entry<Id, Entity> entry, Throwable error) {
        if (ExpectedFailures.isExpected(error)) {
            log.error("ProcessID: {} - Entity {} not written to the broker: {}", processId, entry.getKey().id(), ExpectedFailures.describe(error));
        } else {
            log.error("ProcessID: {} - Entity {} not written to the broker", processId, entry.getKey().id(), error);
        }
        // TODO debug -> info
        log.info("ProcessID: {} - Entity not written to the broker: {}", processId, entry.getValue().value());
    }

    private void logEntitiesNotWritten(String processId, Map<Id, Entity> entitiesById, Map<Id, Entity> writtenEntitiesById) {
        if (writtenEntitiesById.size() < entitiesById.size()) {
            List<String> notWrittenIds = entitiesById.keySet().stream()
                    .filter(id -> !writtenEntitiesById.containsKey(id))
                    .map(Id::id)
                    .toList();
            log.warn("ProcessID: {} - Wrote {} of {} received entities to the broker; not written: {}", processId, writtenEntitiesById.size(), entitiesById.size(), notWrittenIds);
        }
    }
}