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

    private static final String[] BROKER_ENTITY_TYPES = {
            "individual",
            "organization",
            "catalog",
            "product-offering",
            "product-offering-price",
            "product-specification",
            "service-specification",
            "resource-specification",
            "category",
            "product-order",
            "product",
            "usage",
            "usage-specification",
            "applied-customer-bill-rate",
            "customer-bill"
    };

    @Override
    public Mono<Void> synchronizeData(String processId) {
        log.info("ProcessID: {} - Starting P2P Data Synchronization Workflow", processId);

        return Flux.fromIterable(Arrays.asList(BROKER_ENTITY_TYPES))
                .concatMap(entityType ->
                        createLocalMvEntitiesByType(processId, entityType)
                                .switchIfEmpty(Mono.defer(() -> {
                                    log.debug("ProcessID: {} - No local MV Entities found for entity type: {}", processId, entityType);
                                    return Mono.just(Collections.emptyList());
                                }))
                                .flatMap(localMvEntities4DataNegotiation -> {
                                    log.debug("ProcessID: {} - Local MV Entities 4 Data Negotiation synchronizing data: {}", processId, localMvEntities4DataNegotiation);
                                    return filterReplicableMvEntities(processId, localMvEntities4DataNegotiation)
                                            .switchIfEmpty(Mono.defer(() -> {
                                                log.debug("ProcessID: {} - No replicable MV Entities found", processId);
                                                return Mono.just(Collections.emptyList());
                                            }))
                                            .flatMap(replicableMvEntities ->
                                                    getExternalMVEntities4DataNegotiationByIssuer(processId, replicableMvEntities, entityType)
                                                            .flatMap(mvEntities4DataNegotiationByIssuer -> {
                                                                Mono<Map<Issuer, List<MVEntity4DataNegotiation>>> externalMVEntities4DataNegotiationByIssuerMono = Mono.just(mvEntities4DataNegotiationByIssuer);
                                                                Mono<List<MVEntity4DataNegotiation>> localMVEntities4DataNegotiationMono = Mono.just(localMvEntities4DataNegotiation);

                                                                return dataNegotiationJob.negotiateDataSyncWithMultipleIssuers(processId, externalMVEntities4DataNegotiationByIssuerMono, localMVEntities4DataNegotiationMono);
                                                            }));
                                }))
                .collectList()
                .then();
    }

    private Mono<Map<Issuer, List<MVEntity4DataNegotiation>>> getExternalMVEntities4DataNegotiationByIssuer(String processId, List<MVEntity4DataNegotiation> localMvEntities4DataNegotiation, String entityType) {
        return externalAccessNodesConfig.getExternalAccessNodesUrls()
                .flatMapIterable(externalAccessNodesList -> externalAccessNodesList)
                .flatMap(externalAccessNode -> {
                    log.debug("ProcessID: {} - External Access Node: {}", processId, externalAccessNode);
                    var discoverySyncRequest = new DiscoverySyncRequest(apiConfig.getExternalDomain(), localMvEntities4DataNegotiation);

                    Mono<DiscoverySyncRequest> discoverySyncRequestMono = Mono.just(discoverySyncRequest);

                    return discoverySyncWebClient.makeRequest(processId, Mono.just(externalAccessNode), discoverySyncRequestMono)
                            .map(resultList -> {
                                log.debug("ProcessID: {} - Get DiscoverySync Response. [issuer={}, response={}]", processId, externalAccessNode, resultList);

                                Issuer issuer = new Issuer(externalAccessNode);

                                var filteredEntitiesByType =
                                        resultList
                                                .entities()
                                                .stream()
                                                .filter(mvEntity4DataNegotiation -> Objects.equals(mvEntity4DataNegotiation.type(), entityType))
                                                .toList();

                                log.debug("ProcessID: {} - DiscoverySync Response filtered. [issuer={}, response={}]", processId, externalAccessNode, filteredEntitiesByType);

                                return Map.entry(issuer, filteredEntitiesByType);
                            });
                })
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private Mono<List<MVEntity4DataNegotiation>> filterReplicableMvEntities(
            String processId,
            List<MVEntity4DataNegotiation> localMvEntities4DataNegotiation) {

        List<MVEntityReplicationPoliciesInfo> replicationPoliciesInfoList = localMvEntities4DataNegotiation.stream()
                .map(mvEntity -> new MVEntityReplicationPoliciesInfo(
                        mvEntity.id(),
                        mvEntity.lifecycleStatus(),
                        mvEntity.startDateTime(),
                        mvEntity.endDateTime()))
                .toList();

        return replicationPoliciesService.filterReplicableMvEntitiesList(processId, replicationPoliciesInfoList)
                .map(Id::id)
                .collectList()
                .flatMap(replicableIds -> {
                    Set<String> replicableIdsSet = new HashSet<>(replicableIds);
                    return Flux.fromIterable(localMvEntities4DataNegotiation)
                            .filter(mvEntity -> replicableIdsSet.contains(mvEntity.id()))
                            .collectList();
                });
    }

    @Override
    public Mono<List<MVEntity4DataNegotiation>> dataDiscovery(String processId, Mono<String> issuer, Mono<List<MVEntity4DataNegotiation>> externalMvEntities4DataNegotiationMono) {
        log.info("ProcessID: {} - Starting P2P Data Synchronization Discovery Workflow", processId);

        return Flux.fromIterable(Arrays.asList(BROKER_ENTITY_TYPES))
                .concatMap(entityType ->
                        createLocalMvEntitiesByType(processId, entityType)
                                .flatMap(localMvEntities4DataNegotiation -> {
                                    log.debug("ProcessID: {} - Local MV Entities 4 Data Negotiation: {}", processId, localMvEntities4DataNegotiation);

                                    return externalMvEntities4DataNegotiationMono
                                            .flatMap(externalMvEntities4DataNegotiation -> {
                                                List<MVEntity4DataNegotiation> externalMvEntities4DataNegotiationOfType = externalMvEntities4DataNegotiation
                                                        .stream()
                                                        .filter(mvEntity4DataNegotiation -> Objects.equals(mvEntity4DataNegotiation.type(), entityType))
                                                        .toList();

                                                var localMvEntities4DataNegotiationMono = Mono.just(localMvEntities4DataNegotiation);

                                                var dataNegotiationEvent = new DataNegotiationEvent(processId, issuer, Mono.just(externalMvEntities4DataNegotiationOfType), localMvEntities4DataNegotiationMono);
                                                dataNegotiationEventPublisher.publishEvent(dataNegotiationEvent);

                                                return filterReplicableMvEntities(processId, localMvEntities4DataNegotiation);
                                            });
                                })
                                .doOnSuccess(success -> log.info("ProcessID: {} - P2P Data Synchronization Discovery Workflow successfully.", processId))
                                .doOnError(error -> log.error("ProcessID: {} - Error occurred while processing the P2P Data Synchronization Discovery Workflow: {}", processId, error.getMessage())))
                .flatMap(Flux::fromIterable)
                .collectList();
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
