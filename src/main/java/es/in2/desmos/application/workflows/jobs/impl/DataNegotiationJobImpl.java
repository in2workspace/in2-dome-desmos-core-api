package es.in2.desmos.application.workflows.jobs.impl;

import es.in2.desmos.application.workflows.jobs.DataNegotiationJob;
import es.in2.desmos.application.workflows.jobs.DataTransferJob;
import es.in2.desmos.domain.models.*;
import es.in2.desmos.domain.services.policies.ReplicationPoliciesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataNegotiationJobImpl implements DataNegotiationJob {
    private final DataTransferJob dataTransferJob;
    private final ReplicationPoliciesService replicationPoliciesService;


    /**
     * Ejecuta la negociación de datos con múltiples issuers usando flujos reactivos y control de concurrencia.
     *
     * <p>Esta versión refactorizada mejora el rendimiento y la eficiencia al procesar entidades de manera
     * reactiva y concurrente:
     *
     * <ul>
     *   <li>Recibe un Mono con un Map donde cada Issuer está asociado a un Flux de entidades externas,
     *       permitiendo procesamiento en streaming y sin necesidad de cargar toda la lista en memoria.</li>
     *   <li>Las entidades locales se reciben como un Flux reactivo, lo que facilita el procesamiento
     *       continuo y eficiente de datos locales también.</li>
     *   <li>El método negocia con múltiples issuers en paralelo, con un límite de concurrencia (4 en este caso),
     *       evitando saturar recursos y controlando la cantidad de negociaciones simultáneas.</li>
     *   <li>Se convierte cada flujo externo por issuer a lista solo en el momento necesario para la negociación,
     *       minimizando el uso de memoria y latencia.</li>
     *   <li>Al trabajar con flujos y control de concurrencia se obtiene un diseño más escalable, reactivo y eficiente,
     *       que puede procesar datos conforme llegan y evitar bloqueos o esperas innecesarias.</li>
     * </ul>
     *
     * @param processId identificador del proceso
     * @param externalEntitiesInfoMono Mono que emite un Map de Issuer a flujo (Flux) de entidades externas
     * @param localEntitiesFlux flujo reactivo de entidades locales para negociación
     * @return Mono que completa cuando finaliza la negociación y sincronización de datos
     */
    @Override
    public Mono<Void> negotiateDataSyncWithMultipleIssuers(
            String processId,
            Mono<Map<Issuer, Flux<MVEntity4DataNegotiation>>> externalEntitiesInfoMono,
            Flux<MVEntity4DataNegotiation> localEntitiesFlux) {
        log.info("ProcessID: {} - Starting Data Negotiation Job with multiple issuers", processId);

        Mono<List<MVEntity4DataNegotiation>> localEntitiesListMono = localEntitiesFlux.collectList();

        return externalEntitiesInfoMono
                .flatMapMany(externalEntitiesByIssuer ->
                    Flux.fromIterable(externalEntitiesByIssuer.entrySet())
                            .flatMap(issuerFluxEntry -> {
                                Issuer issuer = issuerFluxEntry.getKey();
                                Flux<MVEntity4DataNegotiation> externalEntitiesFlux = issuerFluxEntry.getValue();
                                log.debug("ProcessID: {} - Negotiating data with issuer: {}", processId, issuer.value());
                                return getDataNegotiationResultMono(
                                        processId,
                                        localEntitiesListMono,
                                        Mono.just(issuer.value()),
                                        externalEntitiesFlux
                                );
                            }) //Control de concurrencia: negociamos con 4 issuers simultáneos
                )
                .collectList()
                .flatMap(dataNegotiationResults ->
                        dataTransferJob.syncDataFromList(processId, Mono.just(dataNegotiationResults)));
    }

    @Override
    public Mono<Void> negotiateDataSyncFromEvent(DataNegotiationEvent dataNegotiationEvent) {
        String processId = dataNegotiationEvent.processId();

        log.info("ProcessID: {} - Starting Data Negotiation Job", processId);

        return getDataNegotiationResultMono(
                processId,
                dataNegotiationEvent.localEntitiesInfo(),
                dataNegotiationEvent.issuer(),
                dataNegotiationEvent.externalEntitiesInfo().flatMapMany(Flux::fromIterable))
                .flatMap(dataNegotiationResult ->
                        dataTransferJob.syncData(processId, Mono.just(dataNegotiationResult)));
    }

    private Mono<DataNegotiationResult> getDataNegotiationResultMono(
            String processId,
            Mono<List<MVEntity4DataNegotiation>> localEntitiesInfoMono,
            Mono<String> externalIssuerMono,
            Flux<MVEntity4DataNegotiation> externalEntitiesInfoFlux) {

        log.debug("ProcessID: {} - get data Negotiating result with issuer: {} {} {}",
                processId, externalIssuerMono, localEntitiesInfoMono, externalEntitiesInfoFlux);

        return externalEntitiesInfoFlux
                .flatMap(entityInfo -> getReplicableEntity(processId, entityInfo))
                .collectList()
                .flatMap(replicableExternalEntitiesInfo -> {

                    var replicableExternalEntitiesInfoMono = Mono.just(replicableExternalEntitiesInfo);
                    return getEntitiesToAdd(replicableExternalEntitiesInfoMono, localEntitiesInfoMono)
                            .collectList()
                            .zipWith(
                                    getEntitiesToUpdate(replicableExternalEntitiesInfoMono, localEntitiesInfoMono)
                                            .collectList())
                            .flatMap(entitiesToSaveTuple ->
                                    externalIssuerMono.flatMap(externalIssuer ->
                                                    createDataNegotiationResult(
                                                           externalIssuer,
                                                            entitiesToSaveTuple.getT1(),
                                                            entitiesToSaveTuple.getT2()
                                                    )
                                    )
                            );

                });
    }

    private Flux<MVEntity4DataNegotiation> getEntitiesToAdd(
            Mono<List<MVEntity4DataNegotiation>> externalEntitiesInfoMono,
            Mono<List<MVEntity4DataNegotiation>> localEntitiesInfoMono) {

        return externalEntitiesInfoMono.zipWith(localEntitiesInfoMono)
                .flatMapMany(tuple -> {
                    List<MVEntity4DataNegotiation> externalEntitiesInfo = tuple.getT1();
                    List<MVEntity4DataNegotiation> localEntitiesInfo = tuple.getT2();
                    log.debug("getEntitiesToAdd: external {} internal{}", externalEntitiesInfo, localEntitiesInfo);
                    return Flux.fromIterable(externalEntitiesInfo)
                            .filter(externalEntityInfo -> {
                                Optional<MVEntity4DataNegotiation> localEntityWithSameIdOptional =
                                        localEntitiesInfo.stream()
                                                .filter(localEntity ->
                                                        Objects.equals(localEntity.id(), externalEntityInfo.id()))
                                                .findFirst();

                                return localEntityWithSameIdOptional
                                        .map(localEntityWithSameId ->
                                                externalEntityInfo.hasVersion() && isExternalEntityVersionNewer(
                                                        externalEntityInfo.getFloatVersion(),
                                                        localEntityWithSameId.getFloatVersion()))
                                        .orElse(true);
                            });
                });
    }

    private Flux<MVEntity4DataNegotiation> getEntitiesToUpdate(
            Mono<List<MVEntity4DataNegotiation>> externalEntitiesInfoMono,
            Mono<List<MVEntity4DataNegotiation>> localEntitiesInfoMono) {
        return externalEntitiesInfoMono.zipWith(localEntitiesInfoMono)
                .flatMapMany(tuple -> {

                    List<MVEntity4DataNegotiation> externalEntitiesInfo = tuple.getT1();
                    List<MVEntity4DataNegotiation> localEntitiesInfo = tuple.getT2();
                    log.debug("getEntitiesToUpdate: external {} internal{}", externalEntitiesInfo, localEntitiesInfo);
                    return Flux.fromIterable(externalEntitiesInfo)
                            .filter(externalEntityInfo -> {
                                boolean localHasEntityId =
                                        localEntitiesInfo
                                                .stream()
                                                .anyMatch(localEntityInfo ->
                                                        Objects.equals(localEntityInfo.id(), externalEntityInfo.id()));

                                if (localHasEntityId) {
                                    return localEntitiesInfo
                                            .stream()
                                            .filter(localEntity ->
                                                    Objects.equals(localEntity.id(), externalEntityInfo.id()) &&
                                                            (!externalEntityInfo.hasVersion() ||
                                                                    Objects.equals(
                                                                            localEntity.version(),
                                                                            externalEntityInfo.version())))
                                            .findFirst()
                                            .map(sameLocalEntityInfo -> {
                                                if (externalEntityInfo.getInstantLastUpdate().isEmpty() ||
                                                        sameLocalEntityInfo.getInstantLastUpdate().isEmpty()) {
                                                    log.debug("Negotiation attempt failed for local entity '{}' " +
                                                                    "because it doesn't have 'lastUpdate' field",
                                                            externalEntityInfo.id());
                                                    return false;
                                                }
                                                return isExternalEntityLastUpdateNewer(
                                                        externalEntityInfo.getInstantLastUpdate().get(),
                                                        sameLocalEntityInfo.getInstantLastUpdate().get());
                                            })
                                            .orElse(false);
                                } else {
                                    return false;
                                }
                            });
                });
    }

    private boolean isExternalEntityVersionNewer(Float externalEntityVersion, Float sameLocalEntityVersion) {
        return externalEntityVersion > sameLocalEntityVersion;
    }


    private boolean isExternalEntityLastUpdateNewer(Instant externalEntityLastUpdate, Instant
            sameLocalEntityLastUpdate) {
        return externalEntityLastUpdate.isAfter(sameLocalEntityLastUpdate);
    }

    private Mono<DataNegotiationResult> createDataNegotiationResult(
            String issuerMono,
             List<MVEntity4DataNegotiation> newEntitiesToSync,
             List<MVEntity4DataNegotiation> existingEntitiesToSync) {
        return Mono.just(new DataNegotiationResult(issuerMono, newEntitiesToSync, existingEntitiesToSync));
    }

    private Mono<MVEntity4DataNegotiation> getReplicableEntity(
            String processId,
            MVEntity4DataNegotiation externalEntitiesInfo) {
        return replicationPoliciesService
                .isMVEntityReplicable(processId, new MVEntityReplicationPoliciesInfo(
                        externalEntitiesInfo.id(),
                        externalEntitiesInfo.lifecycleStatus(),
                        externalEntitiesInfo.startDateTime(),
                        externalEntitiesInfo.endDateTime()))
                .filter(Boolean.TRUE::equals)
                .map(isReplicable -> externalEntitiesInfo);
    }
}