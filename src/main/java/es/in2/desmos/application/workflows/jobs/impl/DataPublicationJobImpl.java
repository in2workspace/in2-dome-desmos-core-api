package es.in2.desmos.application.workflows.jobs.impl;

import es.in2.desmos.application.workflows.jobs.DataPublicationJob;
import es.in2.desmos.domain.models.*;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
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
    private final AuditRecordService auditRecordService;
    private final BrokerPublisherService brokerPublisherService;

    public Mono<Void> verifyData(String processId, Mono<String> issuer, Mono<Map<Id, Entity>> entitiesByIdMono, Mono<List<MVEntity4DataNegotiation>> allMVEntity4DataNegotiation, Mono<Map<Id, HashAndHashLink>> existingEntitiesOriginalValidationDataById) {
        log.info("ProcessID: {} - Starting Data Verification Job", processId);

        return buildAndSaveAuditRecordFromDataSync(processId, issuer, entitiesByIdMono, allMVEntity4DataNegotiation, AuditRecordStatus.RETRIEVED)
                .then(createEntitiesToContextBroker(processId, entitiesByIdMono))
                .then(buildAndSaveAuditRecordFromDataSync(processId, issuer, entitiesByIdMono, allMVEntity4DataNegotiation, AuditRecordStatus.PUBLISHED));
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

    private Mono<Void> createEntitiesToContextBroker(String processId, Mono<Map<Id, Entity>> entitiesByIdMono) {
        return entitiesByIdMono
                .flatMapIterable(Map::entrySet)
                .flatMap(x -> brokerPublisherService.publishDataToBroker(processId, x.getKey().id(), x.getValue().value()))
                .collectList()
                .then();
    }
}