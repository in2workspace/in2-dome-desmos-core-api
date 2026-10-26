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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /**
     * Recoge las entidades locales replicables de todos los tipos, hace una única discovery por nodo génesis
     * con todas ellas y negocia tipo a tipo (en el orden de ROOT_OBJECTS_LIST) con la respuesta ya recibida.
     *
     * <p>El nodo remoto procesa todos los tipos en cada discovery, así que una petición por tipo le obligaba a
     * repetir el trabajo completo tantas veces como tipos hay.
     */
    @Override
    public Mono<Void> synchronizeData(String processId) {
        return Flux.fromIterable(ROOT_OBJECTS_LIST)
                .concatMap(entityType -> {
                            log.debug("ProcessID: {} - Starting P2P Data Synchronization with Genesis Nodes for entity type: {}", processId, entityType);
                            return createLocalMvEntitiesByType(processId, entityType)
                                    .switchIfEmpty(Flux.defer(() -> {
                                        log.warn("ProcessID: {} - No local MV Entities found in P2P Data Synchronization for entity type: {}", processId, entityType);
                                        return Flux.empty();
                                    }))
                                    .transform(flux -> filterReplicableMvEntities(processId, flux))
                                    .collectList()
                                    .map(replicableMvEntitiesList -> {
                                        if (replicableMvEntitiesList.isEmpty()) {
                                            log.warn("ProcessID: {} -  No local replicable MV Entities found for entity type: {}", processId, entityType);
                                        }
                                        return Map.entry(entityType, replicableMvEntitiesList);
                                    });
                        }
                )
                .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new)
                .flatMap(localMvEntitiesByType -> {
                    List<MVEntity4DataNegotiation> allLocalMvEntities = localMvEntitiesByType.values().stream()
                            .flatMap(List::stream)
                            .toList();

                    // The local entities of each type are read again right before negotiating it: syncing a type
                    // also stores its subentities (e.g. a product-offering brings its prices and specifications),
                    // which belong to later types and must not be fetched a second time.
                    return exchangeMVEntities4DataNegotiationWithGenesisNodes(processId, allLocalMvEntities)
                            .flatMap(externalMvEntitiesByIssuer -> Flux.fromIterable(localMvEntitiesByType.keySet())
                                    .concatMap(entityType -> readLocalReplicableMvEntities(processId, entityType)
                                            .flatMap(currentLocalMvEntities -> dataNegotiationJob.negotiateDataSyncWithMultipleIssuers(
                                                    processId,
                                                    Mono.just(filterExternalMvEntitiesByType(externalMvEntitiesByIssuer, entityType)),
                                                    Flux.fromIterable(currentLocalMvEntities))))
                                    .then());
                })
                .doFirst(() -> log.info("ProcessID: {} - Starting P2P Data Synchronization with Genesis Nodes", processId));
    }

    private Mono<List<MVEntity4DataNegotiation>> readLocalReplicableMvEntities(String processId, String entityType) {
        return createLocalMvEntitiesByType(processId, entityType)
                .transform(flux -> filterReplicableMvEntities(processId, flux))
                .collectList();
    }

    /**
     * Hace una discovery con cada nodo génesis enviando las entidades locales de todos los tipos y devuelve,
     * por issuer, la lista completa de entidades externas recibidas. La respuesta se recoge entera para poder
     * repartirla después entre los tipos sin repetir la petición.
     *
     * @param processId identificador del proceso
     * @param localMvEntities4DataNegotiation entidades MV locales replicables de todos los tipos
     * @return Mono con mapa de Issuer a la lista de entidades externas recibidas de ese nodo
     */
    private Mono<Map<Issuer, List<MVEntity4DataNegotiation>>> exchangeMVEntities4DataNegotiationWithGenesisNodes(
            String processId,
            List<MVEntity4DataNegotiation> localMvEntities4DataNegotiation) {
        return externalAccessNodesConfig.getExternalAccessNodesUrls()
                .flatMapIterable(externalAccessNodesList -> externalAccessNodesList)
                .flatMap(externalAccessNode -> {
                    log.debug("ProcessID: {} - Exchanging MV Entities with External Genesis Node: {}", processId, externalAccessNode);

                    return discoverySyncWebClient.makeRequest(
                                processId,
                                Mono.just(externalAccessNode),
                                apiConfig.getExternalDomain(),
                                Flux.fromIterable(localMvEntities4DataNegotiation))
                            .collectList()
                            .doOnNext(externalMvEntities ->
                                log.debug("ProcessId: {} - Received {} external MV entities from node {}",
                                        processId, externalMvEntities.size(), externalAccessNode))
                            .map(externalMvEntities -> Map.entry(new Issuer(externalAccessNode), externalMvEntities));
                })
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private Map<Issuer, Flux<MVEntity4DataNegotiation>> filterExternalMvEntitiesByType(
            Map<Issuer, List<MVEntity4DataNegotiation>> externalMvEntitiesByIssuer,
            String entityType) {
        Map<Issuer, Flux<MVEntity4DataNegotiation>> filteredByIssuer = new HashMap<>();
        externalMvEntitiesByIssuer.forEach((issuer, externalMvEntities) ->
                filteredByIssuer.put(issuer, Flux.fromIterable(externalMvEntities.stream()
                        .filter(entity -> Objects.equals(entity.type(), entityType))
                        .toList())));
        return filteredByIssuer;
    }

    private Flux<MVEntity4DataNegotiation> filterReplicableMvEntities(String processId,
                                                                      Flux<MVEntity4DataNegotiation> localMvEntities4DataNegotiationFlux) {
        Flux<MVEntity4DataNegotiation> cachedFlux = localMvEntities4DataNegotiationFlux
                .cache();

        Flux<MVEntityReplicationPoliciesInfo> policyInfoFlux  =
                cachedFlux.map(mv ->
                        new MVEntityReplicationPoliciesInfo(
                            mv.id(),
                            mv.type(),
                            mv.lifecycleStatus(),
                            mv.startDateTime(),
                            mv.endDateTime()
        ));

        return replicationPoliciesService.filterReplicableMvEntitiesList(processId, policyInfoFlux)
                .map(Id::id)
                .collect(Collectors.toSet())
                .flatMapMany(replicableIds ->
                    cachedFlux
                        .filter(mv -> replicableIds.contains(mv.id()))
                        .doOnError(e -> log.error("ProcessID: {} - Error filtering replicable entities: {}",
                                processId, e.getMessage()))

        );
    }

    @Override
    public Flux<MVEntity4DataNegotiation> dataDiscovery(String processId, Mono<String> issuer,
                                                        Flux<MVEntity4DataNegotiation> externalMvEntities4DataNegotiation) {
        Flux<MVEntity4DataNegotiation> cachedExternalFlux = externalMvEntities4DataNegotiation.cache();
        return Flux.fromIterable(ROOT_OBJECTS_LIST)
                .doFirst(() -> log.info("ProcessID: {} - Starting P2P Data Discovery Workflow", processId))
                .concatMap(entityType -> {
                            log.debug("ProcessID: {} - Starting P2P Data Discovery for entity type: {}", processId, entityType);
                            return createLocalMvEntitiesByType(processId, entityType)
                                    .switchIfEmpty(Flux.defer(() -> {
                                        log.warn("ProcessID: {} - No local MV Entities found in P2P Data Discovery for entity type: {}", processId, entityType);
                                        return Flux.empty();
                                    }))
                                    .collectList()
                                    .flatMapMany(localMvEntities4DataNegotiation -> {
                                        Flux<MVEntity4DataNegotiation> externalFilteredFlux = cachedExternalFlux
                                                .filter(mv -> mv.type().equals(entityType));

                                        return externalFilteredFlux
                                                .collectList()
                                                .flatMapMany(externalList -> {
                                                    var dataNegotiationEvent = new DataNegotiationEvent(
                                                            processId,
                                                            issuer,
                                                            Mono.just(externalList),
                                                            Mono.just(localMvEntities4DataNegotiation)
                                                    );
                                                    dataNegotiationEventPublisher.publishEvent(dataNegotiationEvent);
                                                    return filterReplicableMvEntities(processId,
                                                            Flux.fromIterable(localMvEntities4DataNegotiation));
                                                });
                                    });
                        })
                        .doOnComplete(() -> log.info("ProcessID: {} - P2P Data Discovery Workflow completed successfully.", processId))
                        .doOnError(error -> log.error("ProcessID: {} - Error occurred during P2P Data Discovery Workflow: {}", processId, error.getMessage()));
    }


    @Override
    public Flux<Entity> getLocalEntitiesByIdInBase64(String processId, Mono<List<Id>> ids) {
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
                .flatMapMany(mvBrokerEntities ->
                        auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(
                                        processId,
                                        entityType,
                                        Flux.fromIterable(mvBrokerEntities).map(BrokerEntityWithIdTypeLastUpdateAndVersion::getId))
                                .collectList()
                                .flatMapMany(mvAuditEntities -> {
                                    Map<String, MVAuditServiceEntity4DataNegotiation> mvAuditEntitiesById = getMvAuditEntitiesById(mvAuditEntities);

                                    return Flux.fromIterable(mvBrokerEntities)
                                            .map(mvBrokerEntity ->
                                                    toMVEntity4DataNegotiation(mvBrokerEntity, entityType, mvAuditEntitiesById));
                                }));
    }

    private Map<String, MVAuditServiceEntity4DataNegotiation> getMvAuditEntitiesById(List<MVAuditServiceEntity4DataNegotiation> mvAuditEntities) {
        return mvAuditEntities.stream()
                .collect(Collectors.toMap(MVAuditServiceEntity4DataNegotiation::id, Function.identity()));
    }

    private MVEntity4DataNegotiation toMVEntity4DataNegotiation(BrokerEntityWithIdTypeLastUpdateAndVersion mvBrokerEntity,
            String entityType,
            Map<String, MVAuditServiceEntity4DataNegotiation> mvAuditEntitiesById) {

        String entityId = mvBrokerEntity.getId();
        MVAuditServiceEntity4DataNegotiation mvAuditEntity = mvAuditEntitiesById.get(entityId);

        BrokerEntityValidFor validFor = mvBrokerEntity.getValidFor();
        String startDateTime = validFor != null ? validFor.startDateTime() : null;
        String endDateTime = validFor != null ? validFor.endDateTime() : null;

        String hash = mvAuditEntity != null ? mvAuditEntity.hash() : null;
        String hashLink = mvAuditEntity != null ? mvAuditEntity.hashlink() : null;

        return new MVEntity4DataNegotiation(
                entityId,
                entityType,
                mvBrokerEntity.getVersion(),
                mvBrokerEntity.getLastUpdate(),
                mvBrokerEntity.getLifecycleStatus(),
                startDateTime,
                endDateTime,
                hash,
                hashLink
        );
    }
}
