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
     * Refactorizado para mantener datos como Flux el mayor tiempo posible,
     * permitiendo procesamiento en streaming, menor uso de memoria y mejor rendimiento.
     * Solo se colectan listas cuando es estrictamente necesario (e.g. replicables).
     * Mejora la escalabilidad y se alinea con el enfoque reactivo.
     */
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
                        .transform(flux -> filterReplicableMvEntities(processId,flux))
                        .collectList()
                        .flatMap(replicableMvEntitiesList -> {
                            if (replicableMvEntitiesList.isEmpty()) {
                                log.debug("ProcessID: {} -  No replicable MV Entities found, replicableMvEntitiesList is EMPTY", processId);
                            }
                            // Convertimos la lista de replicables de nuevo a Flux para pasarlo
                            Flux<MVEntity4DataNegotiation> replicableEntitiesFlux = Flux.fromIterable(replicableMvEntitiesList);
                            // Obtenemos el Mono<Map<Issuer, Flux<MVEntity4DataNegotiation>>> como antes
                            Mono<Map<Issuer, Flux<MVEntity4DataNegotiation>>> externalEntitiesMono =
                                    getExternalMVEntities4DataNegotiationByIssuer(processId, replicableEntitiesFlux, entityType);
                            // Aquí mantenemos el localEntitiesFlux como flujo
                            Flux<MVEntity4DataNegotiation> localEntitiesFlux = Flux.fromIterable(replicableMvEntitiesList);

                            // Llamamos a la negociación con flujo reactivo
                            return dataNegotiationJob.negotiateDataSyncWithMultipleIssuers(processId, externalEntitiesMono, localEntitiesFlux);
                        })
                ).then();
    }

    /**
     * Obtiene un Mono que emite un Map donde la clave es el Issuer y el valor es un Flux
     * de MVEntity4DataNegotiation para ese issuer.
     *
     * <p>No se recoge la lista completa, sino que se devuelve el flujo tal cual (sin consumirlo todavía).
     * El valor asociado al issuer en el mapa es un Flux reactivo y "vivo" que emitirá entidades conforme
     * las reciba. Se pospone la suscripción y consumo hasta que el flujo se consuma más abajo.
     *
     * <p>Esto puede mejorar eficiencia, porque no se espera a tener toda la lista para procesar,
     * permitiendo procesamiento en streaming (reactividad completa).
     *
     * @param processId identificador del proceso
     * @param localMvEntities4DataNegotiation flujo local de entidades MV para negociación
     * @param entityType tipo de entidad a filtrar
     * @return Mono con mapa de Issuer a flujo de entidades filtradas
     */
    private Mono<Map<Issuer, Flux<MVEntity4DataNegotiation>>> getExternalMVEntities4DataNegotiationByIssuer(String processId,
                                        Flux<MVEntity4DataNegotiation> localMvEntities4DataNegotiation, String entityType) {
        return externalAccessNodesConfig.getExternalAccessNodesUrls()
                .flatMapIterable(externalAccessNodesList -> externalAccessNodesList)
                .flatMap(externalAccessNode -> {
                    log.debug("ProcessID: {} - External Access Node: {}", processId, externalAccessNode);

                    Flux<MVEntity4DataNegotiation> filteredFlux = discoverySyncWebClient.makeRequest(
                                processId,
                                Mono.just(externalAccessNode),
                                apiConfig.getExternalDomain(),
                                localMvEntities4DataNegotiation)
                            .filter(entity -> Objects.equals(entity.type(), entityType))
                            .doOnNext(filteredEntities ->
                                log.debug("ProcessID: {} - Get DiscoverySync Response filtered. [issuer={}, response={}]",
                                        processId, externalAccessNode, filteredEntities));

                    return Mono.just(Map.entry(new Issuer(externalAccessNode), filteredFlux));
                })
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private Flux<MVEntity4DataNegotiation> filterReplicableMvEntities(String processId,
                                                                      Flux<MVEntity4DataNegotiation> localMvEntities4DataNegotiationFlux) {
        log.debug("ProcessID: {} - Local MV Entities 4 Data Negotiation synchronizing data: {}", processId, localMvEntities4DataNegotiationFlux);
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
                        .filter(mv -> replicableIds.contains(mv.id()))
                        .doOnNext(mv -> log.info("Replicable flux emits: {}", mv))
                        .doOnError(e -> log.error("ProcessID: {} - Error filtering replicable entities: {}",
                                processId, e.getMessage()))

        );
    }

    @Override
    public Flux<MVEntity4DataNegotiation> dataDiscovery(String processId, Mono<String> issuer,
                                                        Flux<MVEntity4DataNegotiation> externalMvEntities4DataNegotiation) {

        return Flux.fromIterable(ROOT_OBJECTS_LIST)
                .concatMap(entityType ->
                        createLocalMvEntitiesByType(processId, entityType)
                                .collectList()
                                .flatMapMany(localMvEntities4DataNegotiation -> {
                                    log.debug("ProcessID: {} -  Local MV Entities size for {}: {}", processId, entityType,
                                            localMvEntities4DataNegotiation.size());

                                    Flux<MVEntity4DataNegotiation> externalFilteredFlux = externalMvEntities4DataNegotiation
                                            .filter(mv -> Objects.equals(mv.type(), entityType));

                                    return externalFilteredFlux
                                            .collectList()
                                            .doOnNext(list ->
                                                    log.debug("ProcessID: {} - External MV Entities size for {}: {}",
                                                            processId, entityType, list.size()))
                                            //.zipWith((Mono.fromSupplier(() -> localMvEntities4DataNegotiation)))
                                            .flatMapMany(externalList -> {
                                                var dataNegotiationEvent = new DataNegotiationEvent(
                                                        processId,
                                                        issuer,
                                                        //Mono.just(tuple.getT1()),
                                                        //Mono.just(tuple.getT2())
                                                        Mono.just(externalList),
                                                        Mono.just(localMvEntities4DataNegotiation)
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

        log.info("ProcessID: {} - createLocalMvEntitiesByType for : {}", processId, entityType);

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
