package es.in2.desmos.application.workflows.jobs.impl;

import es.in2.desmos.application.workflows.jobs.DataNegotiationJob;
import es.in2.desmos.application.workflows.jobs.P2PDataSyncJob;
import es.in2.desmos.domain.events.DataNegotiationEventPublisher;
import es.in2.desmos.domain.models.*;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import es.in2.desmos.domain.services.policies.ReplicationPoliciesService;
import es.in2.desmos.domain.services.sync.DiscoverySyncWebClient;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.infrastructure.configs.ExternalAccessNodesConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static es.in2.desmos.domain.utils.ApplicationConstants.ROOT_OBJECTS_LIST;

@Slf4j
@Service
@RequiredArgsConstructor
public class P2PDataSyncJobImpl implements P2PDataSyncJob {
    private final ExternalAccessNodesConfig externalAccessNodesConfig;

    private final ApiConfig apiConfig;

    private final BrokerPublisherService brokerPublisherService;

    private final AuditRecordService auditRecordService;

    private final DataNegotiationEventPublisher dataNegotiationEventPublisher;

    private final DataNegotiationJob dataNegotiationJob;

    private final DiscoverySyncWebClient discoverySyncWebClient;

    private final ReplicationPoliciesService replicationPoliciesService;

    @Override
    public Mono<Void> synchronizeData(String processId) {
        log.info("ProcessID: {} - Starting P2P Data Synchronization Workflow", processId);

        return Flux.fromIterable(ROOT_OBJECTS_LIST)
                .concatMap(entityType ->
                    createLocalMvEntitiesByType(processId, entityType)
                        .switchIfEmpty(Flux.defer(() -> {
                            log.debug("ProcessID: {} - No local MV Entities found for entity type: {}", processId, entityType);
                            return Flux.empty();
                        }))
                        .collectList()
                        .flatMap(localMvEntities4DataNegotiation -> {
                            log.debug("ProcessID: {} - Local MV Entities 4 Data Negotiation synchronizing data: {}", processId, localMvEntities4DataNegotiation);

                            return filterReplicableMvEntities(processId, Flux.fromIterable(localMvEntities4DataNegotiation))
                                    .collectList() // <-- agrupamos el Flux en List como antes
                                    .flatMap(replicableMvEntities -> {
                                        if (replicableMvEntities.isEmpty()) {
                                            log.debug("ProcessID: {} - No replicable MV Entities found", processId);
                                            return Mono.empty();
                                        }
                                        //TODO: REFACTORIZAR A FLUX replicableMvEntities?
                                        return getExternalMVEntities4DataNegotiationByIssuer(processId, Flux.fromIterable(replicableMvEntities), entityType)
                                                .flatMap(mvEntities4DataNegotiationByIssuer -> {
                                                    Mono<Map<Issuer, List<MVEntity4DataNegotiation>>> externalMVEntities4DataNegotiationByIssuerMono = Mono.just(mvEntities4DataNegotiationByIssuer);
                                                    Mono<List<MVEntity4DataNegotiation>> localMVEntities4DataNegotiationMono = Mono.just(localMvEntities4DataNegotiation);

                                                    return dataNegotiationJob.negotiateDataSyncWithMultipleIssuers(
                                                            processId,
                                                            externalMVEntities4DataNegotiationByIssuerMono,
                                                            localMVEntities4DataNegotiationMono
                                                    );
                                                });
                                    });
                        })
                )
                .collectList()
                .then();
    }

    private Mono<Map<Issuer, List<MVEntity4DataNegotiation>>> getExternalMVEntities4DataNegotiationByIssuer(String processId,
                                        Flux<MVEntity4DataNegotiation> localMvEntities4DataNegotiation, String entityType) {
        return externalAccessNodesConfig.getExternalAccessNodesUrls()
                .flatMapIterable(externalAccessNodesList -> externalAccessNodesList)
                .flatMap(externalAccessNode -> {
                    log.debug("ProcessID: {} - External Access Node: {}", processId, externalAccessNode);

                    return discoverySyncWebClient.makeRequest(processId, Mono.just(externalAccessNode), localMvEntities4DataNegotiation)
                            .filter(entity -> Objects.equals(entity.type(), entityType))
                            .collectList()
                            .map(filteredEntities -> {
                                log.debug("ProcessID: {} - Get DiscoverySync Response filtered. [issuer={}, response={}]", processId, externalAccessNode, filteredEntities);
                                return Map.entry(new Issuer(externalAccessNode), filteredEntities);
                            });
                })
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private Flux<MVEntity4DataNegotiation> filterReplicableMvEntities(String processId,
                                                                      Flux<MVEntity4DataNegotiation> localMvEntities4DataNegotiationFlux) {

        Flux<MVEntity4DataNegotiation> cachedFlux = localMvEntities4DataNegotiationFlux.cache();

        Flux<MVEntityReplicationPoliciesInfo> policyInfoFlux  =
                cachedFlux.map(mv ->
                        new MVEntityReplicationPoliciesInfo(
                            mv.id(),
                            mv.lifecycleStatus(),
                            mv.startDateTime(),
                            mv.endDateTime()
        ));

        return replicationPoliciesService.filterReplicableMvEntitiesList(processId, policyInfoFlux)
                .map(Id::id)
                .collect(Collectors.toSet())
                .flatMapMany(replicableIds ->
                    cachedFlux
                        .doOnNext(mv -> log.info("Replicable flux emits: {}", mv))
                        .filter(mv -> replicableIds.contains(mv.id()))
        );
    }

    @Override
    public Flux<MVEntity4DataNegotiation> dataDiscovery(String processId, Mono<String> issuer,
                                                        Flux<MVEntity4DataNegotiation> externalMvEntities4DataNegotiation) {
        log.info("ProcessID: {} - Starting P2P Data Synchronization Discovery Workflow", processId);

        return Flux.fromIterable(ROOT_OBJECTS_LIST)
                .concatMap(entityType ->
                        createLocalMvEntitiesByType(processId, entityType)
                                .collectList()
                                .flatMapMany(localMvEntities4DataNegotiation -> {
                                    log.debug("ProcessID: {} - Local MV Entities: {}", processId, localMvEntities4DataNegotiation);

                                    Flux<MVEntity4DataNegotiation> externalFilteredFlux = externalMvEntities4DataNegotiation
                                            .filter(mv -> Objects.equals(mv.type(), entityType));

                                    return externalFilteredFlux.collectList()
                                            .zipWith((Mono.fromSupplier(() -> localMvEntities4DataNegotiation)))
                                            .flatMapMany(tuple -> {
                                                var dataNegotiationEvent = new DataNegotiationEvent(
                                                        processId,
                                                        issuer,
                                                        Mono.just(tuple.getT1()),
                                                        Mono.just(tuple.getT2())
                                                );
                                                dataNegotiationEventPublisher.publishEvent(dataNegotiationEvent);

                                                return filterReplicableMvEntities(processId,
                                                        Flux.fromIterable(localMvEntities4DataNegotiation));
                                            });
                                })
                )
                .doOnNext(mv -> log.debug("ProcessID: {} - Emitting MVEntity: {}", processId, mv))
                .doOnComplete(() -> log.info("ProcessID: {} - P2P Data Synchronization Discovery Workflow completed successfully.", processId))
                .doOnError(error -> log.error("ProcessID: {} - Error occurred during P2P Data Synchronization Discovery Workflow: {}", processId, error.getMessage()));
    }


    @Override
    public Mono<List<Entity>> getLocalEntitiesByIdInBase64(String processId, Mono<List<Id>> ids) {
        return brokerPublisherService.findEntitiesAndItsSubentitiesByIdInBase64(processId, ids, new ArrayList<>());
    }

    private Flux<MVEntity4DataNegotiation> createLocalMvEntitiesByType(String processId, String entityType) {

        return brokerPublisherService.findAllIdTypeAndAttributesByType(
                    processId,
                    entityType,
                    "lastUpdate",
                    "version",
                    "lifecycleStatus",
                    "validFor",
                    BrokerEntityWithIdTypeLastUpdateAndVersion.class)
                .collectList()
                .flatMapMany(mvBrokerEntities -> {
                    log.debug("ProcessID: {} - MV Broker Entities 4 Data Negotiation: {}", processId, mvBrokerEntities);

                    return auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(
                            processId,
                            entityType,
                            Flux.fromIterable(mvBrokerEntities).map(BrokerEntityWithIdTypeLastUpdateAndVersion::getId))
                            .collectList()
                            .flatMapMany(mvAuditEntities -> {
                                log.debug("ProcessID: {} - MV Audit Service Entities 4 Data Negotiation: {}", processId, mvAuditEntities);
                                Map<String, MVAuditServiceEntity4DataNegotiation> mvAuditEntitiesById = getMvAuditEntitiesById(mvAuditEntities);

                                return Flux.fromIterable(mvBrokerEntities)
                                        .map(mvBrokerEntity -> {

                                            String entityId = mvBrokerEntity.getId();
                                            MVAuditServiceEntity4DataNegotiation mvAuditEntity = mvAuditEntitiesById.get(entityId);
                                            return new MVEntity4DataNegotiation(
                                                    entityId,
                                                    entityType,
                                                    mvBrokerEntity.getVersion(),
                                                    mvBrokerEntity.getLastUpdate(),
                                                    mvBrokerEntity.getLifecycleStatus(),
                                                    mvBrokerEntity.getValidFor() != null
                                                            ? mvBrokerEntity.getValidFor().startDateTime()
                                                            : null,
                                                    mvBrokerEntity.getValidFor() != null
                                                            ? mvBrokerEntity.getValidFor().endDateTime()
                                                            : null,
                                                    mvAuditEntity != null ? mvAuditEntity.hash() : null,
                                                    mvAuditEntity != null ? mvAuditEntity.hashlink() : null
                                            );
                                        });
                            });
                });
    }

    private Map<String, MVAuditServiceEntity4DataNegotiation> getMvAuditEntitiesById(List<MVAuditServiceEntity4DataNegotiation> mvAuditEntities) {
        return mvAuditEntities.stream()
                .collect(Collectors.toMap(MVAuditServiceEntity4DataNegotiation::id, Function.identity()));
    }
}
