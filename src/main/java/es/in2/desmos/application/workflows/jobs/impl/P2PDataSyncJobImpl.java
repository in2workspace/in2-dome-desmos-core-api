package es.in2.desmos.application.workflows.jobs.impl;

import es.in2.desmos.application.workflows.jobs.DataNegotiationJob;
import es.in2.desmos.application.workflows.jobs.P2PDataSyncJob;
import es.in2.desmos.domain.events.DataNegotiationEventPublisher;
import es.in2.desmos.domain.exceptions.P2PDataSyncException;
import es.in2.desmos.domain.models.*;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import es.in2.desmos.domain.services.policies.ReplicationPoliciesService;
import es.in2.desmos.domain.services.sync.DiscoverySyncWebClient;
import es.in2.desmos.domain.utils.ExpectedFailures;
import es.in2.desmos.domain.utils.LogUtils;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.infrastructure.configs.ExternalAccessNodesConfig;
import es.in2.desmos.infrastructure.configs.properties.LoggingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.stream.Collectors;

import static es.in2.desmos.domain.utils.ApplicationConstants.ROOT_OBJECTS_LIST;
import static es.in2.desmos.domain.utils.LogUtils.P2P_SYNC;

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

    private final LoggingProperties loggingProperties;

    /**
     * Recoge las entidades locales replicables de todos los tipos, hace una única discovery por nodo génesis
     * con todas ellas y negocia tipo a tipo (en el orden de ROOT_OBJECTS_LIST) con la respuesta ya recibida.
     *
     * <p>El nodo remoto procesa todos los tipos en cada discovery, así que una petición por tipo le obligaba a
     * repetir el trabajo completo tantas veces como tipos hay.
     *
     * <p>En INFO deja una línea START, una BEGIN/DONE por tipo y una END con el resumen, todas con el prefijo
     * {@code [p2p-sync]}.
     */
    @Override
    public Mono<Void> synchronizeData(String processId) {
        long startNanos = System.nanoTime();
        int typeCount = ROOT_OBJECTS_LIST.size();
        LongAdder okTypes = new LongAdder();
        Queue<String> failedEntityTypes = new ConcurrentLinkedQueue<>();
        Queue<String> failedGenesisNodes = new ConcurrentLinkedQueue<>();
        LongAdder fetchedEntities = new LongAdder();

        return externalAccessNodesConfig.getExternalAccessNodesUrls()
                .doOnNext(genesisUrls -> log.info("{} ProcessID: {} - START genesis={} types={}",
                        P2P_SYNC, processId, genesisUrls, typeCount))
                .flatMap(genesisUrls -> collectLocalReplicableMvEntitiesByType(processId, failedEntityTypes)
                        .flatMap(localMvEntitiesByType -> {
                            List<MVEntity4DataNegotiation> allLocalMvEntities = localMvEntitiesByType.values().stream()
                                    .flatMap(List::stream)
                                    .toList();

                            return exchangeMVEntities4DataNegotiationWithGenesisNodes(processId, genesisUrls,
                                    allLocalMvEntities, failedGenesisNodes)
                                    .flatMap(externalMvEntitiesByIssuerAndType -> {
                                        if (externalMvEntitiesByIssuerAndType.isEmpty() && !failedGenesisNodes.isEmpty()) {
                                            // Every genesis node failed: there is nothing to negotiate
                                            return Mono.empty();
                                        }
                                        return Flux.fromIterable(localMvEntitiesByType.keySet())
                                                .concatMap(entityType -> negotiateEntityType(processId, entityType,
                                                        localMvEntitiesByType.get(entityType), externalMvEntitiesByIssuerAndType)
                                                        .doOnNext(metrics -> {
                                                            okTypes.increment();
                                                            fetchedEntities.add(metrics.fetched());
                                                        })
                                                        .onErrorResume(error -> {
                                                            failedEntityTypes.add(entityType);
                                                            return Mono.empty();
                                                        }))
                                                .then();
                                    });
                        })
                        // Failures are isolated so the rest of the types and nodes still sync, but the synchronization
                        // as a whole must not look successful: it fails at the end if any type or any genesis node
                        // failed, even when the other genesis nodes synced fine.
                        .then(Mono.defer(() -> reportIncompleteSynchronization(genesisUrls, failedEntityTypes, failedGenesisNodes))))
                .doOnSuccess(unused -> log.info("{} ProcessID: {} - END ok={} failed={} entities={} duration={}",
                        P2P_SYNC, processId, okTypes.sum(), failedEntityTypes.size(), fetchedEntities.sum(),
                        LogUtils.formatDurationSince(startNanos)))
                .doOnError(error -> {
                    String message = "{} ProcessID: {} - END with error after {}: ok={} failed={} entities={} cause={}";
                    Object[] arguments = {P2P_SYNC, processId, LogUtils.formatDurationSince(startNanos), okTypes.sum(),
                            failedEntityTypes.size(), fetchedEntities.sum(), error.getMessage()};
                    // An incomplete sync (some types or genesis nodes failed, each already logged) is expected
                    // while a genesis node is degraded, and the next scheduled sync tries again
                    if (error instanceof P2PDataSyncException) {
                        log.warn(message, arguments);
                    } else {
                        log.error(message, arguments);
                    }
                });
    }

    // Any failed type or genesis node leaves data unsynchronized, so the synchronization is reported as incomplete
    // even if the rest succeeded.
    private Mono<Void> reportIncompleteSynchronization(List<String> genesisUrls, Queue<String> failedEntityTypes,
                                                       Queue<String> failedGenesisNodes) {
        if (failedEntityTypes.isEmpty() && failedGenesisNodes.isEmpty()) {
            return Mono.empty();
        }
        return Mono.error(new P2PDataSyncException(String.format(
                "P2P Data Synchronization incomplete: %d of %d entity types failed %s, %d of %d genesis nodes failed %s",
                failedEntityTypes.size(), ROOT_OBJECTS_LIST.size(), failedEntityTypes,
                failedGenesisNodes.size(), genesisUrls.size(), failedGenesisNodes)));
    }

    private Mono<Map<String, List<MVEntity4DataNegotiation>>> collectLocalReplicableMvEntitiesByType(
            String processId, Queue<String> failedEntityTypes) {
        return Flux.fromIterable(ROOT_OBJECTS_LIST)
                .concatMap(entityType -> {
                            log.debug("ProcessID: {} - Starting P2P Data Synchronization with Genesis Nodes for entity type: {}", processId, entityType);
                            return createLocalMvEntitiesByType(processId, entityType)
                                    .transform(flux -> filterReplicableMvEntities(processId, flux))
                                    .collectList()
                                    // An empty type shows as "BEGIN local=0"
                                    .map(replicableMvEntitiesList -> Map.entry(entityType, replicableMvEntitiesList))
                                    .onErrorResume(error -> {
                                        failedEntityTypes.add(entityType);
                                        logFailure("{} ProcessID: {} - ({}/{}) {} FAILED reading local MV Entities, type skipped",
                                                error, P2P_SYNC, processId, typePosition(entityType), ROOT_OBJECTS_LIST.size(), entityType);
                                        return Mono.empty();
                                    });
                        }
                )
                .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new);
    }

    private Mono<P2PSyncTypeMetrics> negotiateEntityType(
            String processId, String entityType, List<MVEntity4DataNegotiation> snapshotLocalMvEntities,
            Map<Issuer, Map<String, List<MVEntity4DataNegotiation>>> externalMvEntitiesByIssuerAndType) {
        Map<Issuer, List<MVEntity4DataNegotiation>> externalOfType =
                externalMvEntitiesOfType(externalMvEntitiesByIssuerAndType, entityType);
        return currentLocalMvEntities(processId, entityType, snapshotLocalMvEntities, externalOfType)
                // Before BEGIN, so negotiateEntityTypeWith does not see this failure
                .doOnError(error -> logFailure("{} ProcessID: {} - ({}/{}) {} FAILED re-reading local MV Entities",
                        error, P2P_SYNC, processId, typePosition(entityType), ROOT_OBJECTS_LIST.size(), entityType))
                .flatMap(localMvEntities -> negotiateEntityTypeWith(processId, entityType, localMvEntities, externalOfType));
    }

    /**
     * Entidades locales con las que negociar un tipo. Sincronizar un tipo también guarda sus subentidades (p. ej. una
     * product-offering trae sus precios y especificaciones), que pertenecen a tipos posteriores y no deben volver a
     * descargarse. Por eso se vuelven a leer del broker, pero solo si el génesis tiene alguna entidad del tipo que no
     * coincide con la lectura inicial: si todas coinciden, la negociación no pediría nada y la lectura inicial vale.
     */
    private Mono<List<MVEntity4DataNegotiation>> currentLocalMvEntities(
            String processId, String entityType,
            List<MVEntity4DataNegotiation> snapshotLocalMvEntities,
            Map<Issuer, List<MVEntity4DataNegotiation>> externalOfType) {
        Map<String, MVEntity4DataNegotiation> snapshotById = snapshotLocalMvEntities.stream()
                .collect(Collectors.toMap(MVEntity4DataNegotiation::id, Function.identity(), (first, second) -> first));
        boolean upToDate = externalOfType.values().stream()
                .flatMap(List::stream)
                .allMatch(external -> isSameVersion(snapshotById.get(external.id()), external));
        return upToDate
                ? Mono.just(snapshotLocalMvEntities)
                : readLocalReplicableMvEntities(processId, entityType);
    }

    private static boolean isSameVersion(MVEntity4DataNegotiation local, MVEntity4DataNegotiation external) {
        return local != null
                && Objects.equals(local.version(), external.version())
                && Objects.equals(local.lastUpdate(), external.lastUpdate())
                && Objects.equals(local.hash(), external.hash());
    }

    private Mono<List<MVEntity4DataNegotiation>> readLocalReplicableMvEntities(String processId, String entityType) {
        return createLocalMvEntitiesByType(processId, entityType)
                .transform(flux -> filterReplicableMvEntities(processId, flux))
                .collectList();
    }

    private Mono<P2PSyncTypeMetrics> negotiateEntityTypeWith(String processId, String entityType,
                                                             List<MVEntity4DataNegotiation> localMvEntities,
                                                             Map<Issuer, List<MVEntity4DataNegotiation>> externalOfType) {
        int position = typePosition(entityType);
        int typeCount = ROOT_OBJECTS_LIST.size();
        int remoteCount = externalOfType.values().stream().mapToInt(List::size).sum();
        P2PSyncTypeMetrics metrics = new P2PSyncTypeMetrics();
        long[] startNanos = new long[1];

        Map<Issuer, Flux<MVEntity4DataNegotiation>> externalFluxByIssuer = new HashMap<>();
        externalOfType.forEach((issuer, entities) -> externalFluxByIssuer.put(issuer, Flux.fromIterable(entities)));

        return dataNegotiationJob.negotiateDataSyncWithMultipleIssuers(
                        processId,
                        Mono.just(externalFluxByIssuer),
                        Flux.fromIterable(localMvEntities))
                .contextWrite(context -> context.put(P2PSyncTypeMetrics.CONTEXT_KEY, metrics))
                .doFirst(() -> {
                    startNanos[0] = System.nanoTime();
                    log.info("{} ProcessID: {} - ({}/{}) {} BEGIN local={}",
                            P2P_SYNC, processId, position, typeCount, entityType, localMvEntities.size());
                })
                .then(Mono.fromSupplier(() -> {
                    log.info("{} ProcessID: {} - ({}/{}) {} DONE remote={} fetched={} subentities={} missing={} invalidIntegrity={} {}",
                            P2P_SYNC, processId, position, typeCount, entityType, remoteCount, metrics.fetched(),
                            metrics.subentities(), metrics.missing(), metrics.invalidIntegrity(),
                            LogUtils.formatDurationSince(startNanos[0]));
                    return metrics;
                }))
                .doOnError(error -> logFailure("{} ProcessID: {} - ({}/{}) {} FAILED after {}",
                        error, P2P_SYNC, processId, position, typeCount, entityType,
                        LogUtils.formatDurationSince(startNanos[0])));
    }

    // Expected failures (a peer or the broker rejecting, unreachable or too slow) in one line; any other keeps its stack trace
    private static void logFailure(String message, Throwable error, Object... arguments) {
        boolean expected = ExpectedFailures.isExpected(error);
        Object[] withCause = Arrays.copyOf(arguments, arguments.length + (expected ? 1 : 2));
        withCause[arguments.length] = ExpectedFailures.describe(error);
        if (!expected) {
            withCause[arguments.length + 1] = error;
        }
        log.error(message + ": {}", withCause);
    }

    private static int typePosition(String entityType) {
        return ROOT_OBJECTS_LIST.indexOf(entityType) + 1;
    }

    /**
     * Hace una discovery con cada nodo génesis enviando las entidades locales de todos los tipos y devuelve,
     * por issuer, las entidades externas recibidas agrupadas por tipo. La respuesta se recoge entera y se agrupa una
     * sola vez para poder repartirla después entre los tipos sin repetir la petición.
     *
     * <p>Si la discovery con un nodo falla, ese nodo se omite, se anota en {@code failedGenesisNodes} y se sigue
     * con el resto.
     *
     * @param processId identificador del proceso
     * @param genesisUrls URLs de los nodos génesis
     * @param localMvEntities4DataNegotiation entidades MV locales replicables de todos los tipos
     * @param failedGenesisNodes nodos génesis cuya discovery ha fallado
     * @return Mono con mapa de Issuer a las entidades externas recibidas de ese nodo, por tipo
     */
    private Mono<Map<Issuer, Map<String, List<MVEntity4DataNegotiation>>>> exchangeMVEntities4DataNegotiationWithGenesisNodes(
            String processId,
            List<String> genesisUrls,
            List<MVEntity4DataNegotiation> localMvEntities4DataNegotiation,
            Queue<String> failedGenesisNodes) {
        Duration slowRequestThreshold = loggingProperties.slowRequestThreshold();
        return Flux.fromIterable(genesisUrls)
                .flatMap(externalAccessNode -> {
                    log.debug("ProcessID: {} - Exchanging MV Entities with External Genesis Node: {}", processId, externalAccessNode);
                    long startNanos = System.nanoTime();

                    return discoverySyncWebClient.makeRequest(
                                processId,
                                Mono.just(externalAccessNode),
                                apiConfig.getExternalDomain(),
                                Flux.fromIterable(localMvEntities4DataNegotiation))
                            .collectList()
                            .doOnNext(externalMvEntities -> {
                                Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
                                log.info("{} ProcessID: {} - discovery genesis={} sent={} received={} {}",
                                        P2P_SYNC, processId, externalAccessNode, localMvEntities4DataNegotiation.size(),
                                        externalMvEntities.size(), LogUtils.formatDuration(elapsed));
                                warnIfSlow(processId, externalAccessNode, "discovery", elapsed, slowRequestThreshold);
                            })
                            .map(externalMvEntities -> Map.entry(new Issuer(externalAccessNode),
                                    groupByType(processId, externalAccessNode, externalMvEntities)))
                            .onErrorResume(error -> {
                                failedGenesisNodes.add(externalAccessNode);
                                Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
                                if (ExpectedFailures.isExpected(error)) {
                                    log.warn("{} ProcessID: {} - Skipping genesis node {} in P2P Data Synchronization: discovery failed after {}: {}",
                                            P2P_SYNC, processId, externalAccessNode, LogUtils.formatDuration(elapsed), ExpectedFailures.describe(error));
                                } else {
                                    log.warn("{} ProcessID: {} - Skipping genesis node {} in P2P Data Synchronization: discovery failed after {}",
                                            P2P_SYNC, processId, externalAccessNode, LogUtils.formatDuration(elapsed), error);
                                }
                                warnIfSlow(processId, externalAccessNode, "discovery", elapsed, slowRequestThreshold);
                                return Mono.empty();
                            });
                })
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private static void warnIfSlow(String processId, String url, String request, Duration elapsed, Duration threshold) {
        if (elapsed.compareTo(threshold) > 0) {
            log.warn("{} ProcessID: {} - Slow request to genesis {}: {} took {} (threshold {})",
                    P2P_SYNC, processId, url, request, LogUtils.formatDuration(elapsed), LogUtils.formatDuration(threshold));
        }
    }

    // Entities without a type cannot belong to any entity type, so they are dropped instead of failing the whole node
    private static Map<String, List<MVEntity4DataNegotiation>> groupByType(String processId, String genesisUrl,
                                                                         List<MVEntity4DataNegotiation> externalMvEntities) {
        long withoutType = externalMvEntities.stream().filter(entity -> entity.type() == null).count();
        if (withoutType > 0) {
            log.warn("{} ProcessID: {} - Ignoring {} external MV entities without type received from genesis {}",
                    P2P_SYNC, processId, withoutType, genesisUrl);
        }
        return externalMvEntities.stream()
                .filter(entity -> entity.type() != null)
                .collect(Collectors.groupingBy(MVEntity4DataNegotiation::type));
    }

    private static Map<Issuer, List<MVEntity4DataNegotiation>> externalMvEntitiesOfType(
            Map<Issuer, Map<String, List<MVEntity4DataNegotiation>>> externalMvEntitiesByIssuerAndType,
            String entityType) {
        Map<Issuer, List<MVEntity4DataNegotiation>> ofType = new HashMap<>();
        externalMvEntitiesByIssuerAndType.forEach((issuer, byType) ->
                ofType.put(issuer, byType.getOrDefault(entityType, List.of())));
        return ofType;
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
                                                // A remote entity without type matches no type instead of failing the discovery
                                                .filter(mv -> entityType.equals(mv.type()));

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
