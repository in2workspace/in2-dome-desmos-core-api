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
                        .switchIfEmpty(Mono.defer(() -> {
                            log.debug("ProcessID: {} - No local MV Entities found for entity type: {}", processId, entityType);
                            return Mono.just(Collections.emptyList());
                        }))
                        .flatMap(localMvEntities4DataNegotiation -> {
                            log.debug("ProcessID: {} - Local MV Entities 4 Data Negotiation synchronizing data: {}", processId, localMvEntities4DataNegotiation);

                            return filterReplicableMvEntities(processId, Flux.fromIterable(localMvEntities4DataNegotiation))
                                    .collectList() // <-- ahora agrupas el Flux en List como antes
                                    .flatMap(replicableMvEntities -> {
                                        if (replicableMvEntities.isEmpty()) {
                                            log.debug("ProcessID: {} - No replicable MV Entities found", processId);
                                            return Mono.empty();
                                        }

                                        return getExternalMVEntities4DataNegotiationByIssuer(processId, replicableMvEntities, entityType)
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

    private Mono<Map<Issuer, List<MVEntity4DataNegotiation>>> getExternalMVEntities4DataNegotiationByIssuer(String processId, List<MVEntity4DataNegotiation> localMvEntities4DataNegotiation, String entityType) {
        return externalAccessNodesConfig.getExternalAccessNodesUrls()
                .flatMapIterable(externalAccessNodesList -> externalAccessNodesList)
                .flatMap(externalAccessNode -> {
                    log.debug("ProcessID: {} - External Access Node: {}", processId, externalAccessNode);
                    var discoverySyncRequest = new DiscoverySyncRequest(Mono.just(apiConfig.getExternalDomain()),  Flux.fromIterable(localMvEntities4DataNegotiation));

                    Mono<DiscoverySyncRequest> discoverySyncRequestMono = Mono.just(discoverySyncRequest);

                    return discoverySyncWebClient.makeRequest(processId, Mono.just(externalAccessNode), discoverySyncRequestMono)
                            .flatMap(resultList -> {
                                log.debug("ProcessID: {} - Get DiscoverySync Response. [issuer={}, response={}]", processId, externalAccessNode, resultList);

                                Issuer issuer = new Issuer(externalAccessNode);

                                return resultList
                                        .entities()
                                        .filter(entity -> Objects.equals(entity.type(), entityType))
                                        .collectList() // volvemos a Mono<List<MVEntity4DataNegotiation>>
                                        .map(filteredEntities -> {
                                            log.debug("ProcessID: {} - DiscoverySync Response filtered. [issuer={}, response={}]", processId, externalAccessNode, filteredEntities);
                                            return Map.entry(issuer, filteredEntities);
                                        });
                            });
                })
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private Flux<MVEntity4DataNegotiation> filterReplicableMvEntities(String processId,
                                                                      Flux<MVEntity4DataNegotiation> localMvEntities4DataNegotiationFlux) {
        return localMvEntities4DataNegotiationFlux
                .publish(sharedFlux -> {
                    var policyInfoFlux = sharedFlux.map(mvEntity -> new MVEntityReplicationPoliciesInfo(
                            mvEntity.id(),
                            mvEntity.lifecycleStatus(),
                            mvEntity.startDateTime(),
                            mvEntity.endDateTime()
                    ));

                    var replicableIdsFlux = replicationPoliciesService.filterReplicableMvEntitiesList(processId, policyInfoFlux)
                            .map(Id::id)
                            .collectList()
                            .map(HashSet::new);

                    return replicableIdsFlux.flatMapMany(replicableIds ->
                            sharedFlux.filter(mv -> replicableIds.contains(mv.id()))
                    );
                });
    }



    @Override
    public Flux<MVEntity4DataNegotiation> dataDiscovery(String processId, Mono<String> issuer,
                                                        Flux<MVEntity4DataNegotiation> externalMvEntities4DataNegotiation) {
        log.info("ProcessID: {} - Starting P2P Data Synchronization Discovery Workflow", processId);

        return Flux.fromIterable(ROOT_OBJECTS_LIST)
                .concatMap(entityType ->
                        createLocalMvEntitiesByType(processId, entityType)
                                .flatMapMany(localMvEntities4DataNegotiation -> {
                                    log.debug("ProcessID: {} - Local MV Entities: {}", processId, localMvEntities4DataNegotiation);

                                    Flux<MVEntity4DataNegotiation> externalFilteredFlux = externalMvEntities4DataNegotiation
                                            .filter(mv -> Objects.equals(mv.type(), entityType));

                                    //TODO: OJITO AQUI
                                    externalFilteredFlux.collectList()
                                            .zipWith(Mono.just(localMvEntities4DataNegotiation))
                                            .subscribe(tuple -> {
                                                var dataNegotiationEvent = new DataNegotiationEvent(
                                                        processId,
                                                        issuer,
                                                        Mono.just(tuple.getT1()),
                                                        Mono.just(tuple.getT2())
                                                );
                                                dataNegotiationEventPublisher.publishEvent(dataNegotiationEvent);
                                            });

                                    return filterReplicableMvEntities(processId, Flux.fromIterable(localMvEntities4DataNegotiation));
                                })
                )
                .doOnNext(mv -> log.debug("ProcessID: {} - Emitting MVEntity: {}", processId, mv))
                .doOnComplete(() -> log.info("ProcessID: {} - P2P Data Synchronization Discovery Workflow completed successfully.", processId))
                .doOnError(error -> log.error("ProcessID: {} - Error occurred during P2P Data Synchronization Discovery Workflow: {}", processId, error.getMessage()));
    }


    @Override
    public Mono<List<Entity>> getLocalEntitiesByIdInBase64(String processId, Mono<List<Id>> ids) {
        return brokerPublisherService
                .findEntitiesAndItsSubentitiesByIdInBase64(processId, ids, new ArrayList<>());
    }

    private static Mono<List<String>> getEntitiesIds(Mono<List<BrokerEntityWithIdTypeLastUpdateAndVersion>> mvBrokerEntities4DataNegotiationMono) {
        return mvBrokerEntities4DataNegotiationMono.map(x -> x.stream().map(BrokerEntityWithIdTypeLastUpdateAndVersion::getId).toList());
    }

    private Mono<List<MVEntity4DataNegotiation>> createLocalMvEntitiesByType(String processId, String entityType) {
        return brokerPublisherService.findAllIdTypeAndAttributesByType(processId, entityType, "lastUpdate", "version", "lifecycleStatus", "validFor", BrokerEntityWithIdTypeLastUpdateAndVersion.class)
                .collectList()
                .flatMap(mvBrokerEntities -> {
                    log.debug("ProcessID: {} - MV Broker Entities 4 Data Negotiation: {}", processId, mvBrokerEntities);

                    Mono<List<String>> entitiesIdsMono = getEntitiesIds(Mono.just(mvBrokerEntities));

                    return auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(processId, entityType, entitiesIdsMono)
                            .flatMap(mvAuditEntities -> {

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
                                                    mvAuditEntity.hash(),
                                                    mvAuditEntity.hashlink()
                                            );
                                        })
                                        .collectList();
                            });
                });
    }

    private Map<String, MVAuditServiceEntity4DataNegotiation> getMvAuditEntitiesById(List<MVAuditServiceEntity4DataNegotiation> mvAuditEntities) {
        return mvAuditEntities.stream()
                .collect(Collectors.toMap(MVAuditServiceEntity4DataNegotiation::id, Function.identity()));
    }
}
