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

@Slf4j
@Service
@RequiredArgsConstructor
public class DataNegotiationJobImpl implements DataNegotiationJob {
    private final DataTransferJob dataTransferJob;
    private final ReplicationPoliciesService replicationPoliciesService;

    @Override
    public Mono<Void> negotiateDataSyncWithMultipleIssuers(
            String processId,
            Mono<Map<Issuer,
                    List<MVEntity4DataNegotiation>>> externalEntitiesInfoMono,
            Mono<List<MVEntity4DataNegotiation>> localEntitiesInfoMono) {
        log.info("ProcessID: {} - Starting Data Negotiation Job with multiple issuers", processId);

        return localEntitiesInfoMono.flatMap(localEntitiesInfo ->
                externalEntitiesInfoMono
                        .flatMapIterable(Map::entrySet)
                        .flatMap(externalEntitiesInfoByIssuer ->
                                getDataNegotiationResultMono(
                                        processId,
                                        localEntitiesInfoMono,
                                        Mono.just(externalEntitiesInfoByIssuer.getKey().value()),
                                        Mono.just(externalEntitiesInfoByIssuer.getValue())))
                        .collectList()
                        .flatMap(dataNegotiationResults ->
                                dataTransferJob.syncDataFromList(processId, Mono.just(dataNegotiationResults))));
    }

    @Override
    public Mono<Void> negotiateDataSyncFromEvent(DataNegotiationEvent dataNegotiationEvent) {
        String processId = dataNegotiationEvent.processId();

        log.info("ProcessID: {} - Starting Data Negotiation Job", processId);

        return getDataNegotiationResultMono(
                processId,
                dataNegotiationEvent.localEntitiesInfo(),
                dataNegotiationEvent.issuer(),
                dataNegotiationEvent.externalEntitiesInfo())
                .flatMap(dataNegotiationResult ->
                        dataTransferJob.syncData(processId, Mono.just(dataNegotiationResult)));
    }

    private Mono<DataNegotiationResult> getDataNegotiationResultMono(
            String processId,
            Mono<List<MVEntity4DataNegotiation>> localEntitiesInfoMono,
            Mono<String> externalIssuerMono,
            Mono<List<MVEntity4DataNegotiation>> externalEntitiesInfoMono) {
        return externalEntitiesInfoMono
                .flatMapMany(Flux::fromIterable)
                .flatMap(entityInfo ->
                        getReplicableEntity(processId, entityInfo))
                .collectList()
                .flatMap(replicableExternalEntitiesInfo -> {
                    var replicableExternalEntitiesInfoMono = Mono.just(replicableExternalEntitiesInfo);
                    return getEntitiesToAdd(replicableExternalEntitiesInfoMono, localEntitiesInfoMono)
                            .zipWith(getEntitiesToUpdate(replicableExternalEntitiesInfoMono, localEntitiesInfoMono))
                            .flatMap(entitiesToSaveTuple ->
                                    externalIssuerMono
                                            .flatMap(externalIssuer ->
                                                    createDataNegotiationResult(
                                                            Mono.just(externalIssuer),
                                                            Mono.just(entitiesToSaveTuple.getT1()),
                                                            Mono.just(entitiesToSaveTuple.getT2()))));

                });
    }

    private Mono<List<MVEntity4DataNegotiation>> getEntitiesToAdd(
            Mono<List<MVEntity4DataNegotiation>> externalEntitiesInfoMono,
            Mono<List<MVEntity4DataNegotiation>> localEntitiesInfoMono) {
        return externalEntitiesInfoMono.zipWith(localEntitiesInfoMono)
                .map(tuple -> {
                    List<MVEntity4DataNegotiation> externalEntitiesInfo = tuple.getT1();
                    List<MVEntity4DataNegotiation> localEntitiesInfo = tuple.getT2();

                    return externalEntitiesInfo
                            .stream()
                            .filter(externalEntityInfo -> {
                                List<Float> localVersionsForSameId =
                                        localEntitiesInfo
                                                .stream()
                                                .filter(localEntityInfo ->
                                                        Objects.equals(localEntityInfo.id(), externalEntityInfo.id()))
                                                .map(MVEntity4DataNegotiation::getFloatVersion)
                                                .toList();
                                boolean localEntityIdMissing = localVersionsForSameId.isEmpty();

                                if (localEntityIdMissing) {
                                    return true;
                                } else {
                                    boolean isExternalEntityVersionNewer =
                                            localVersionsForSameId
                                                    .stream()
                                                    .allMatch(localVersion ->
                                                            isExternalEntityVersionNewer(
                                                                    externalEntityInfo.getFloatVersion(),
                                                                    localVersion));
                                    return externalEntityInfo.hasVersion() &&
                                            isExternalEntityVersionNewer;
                                }
                            })
                            .toList();
                });
    }

    private Mono<List<MVEntity4DataNegotiation>> getEntitiesToUpdate(
            Mono<List<MVEntity4DataNegotiation>> externalEntitiesInfoMono,
            Mono<List<MVEntity4DataNegotiation>> localEntitiesInfoMono) {
        return externalEntitiesInfoMono.zipWith(localEntitiesInfoMono)
                .map(tuple -> {

                    List<MVEntity4DataNegotiation> externalEntitiesInfo = tuple.getT1();
                    List<MVEntity4DataNegotiation> localEntitiesInfo = tuple.getT2();

                    return externalEntitiesInfo
                            .stream()
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
                                            .map(sameLocalEntityInfo ->
                                                    isExternalEntityLastUpdateNewer(
                                                            externalEntityInfo.getInstantLastUpdate(),
                                                            sameLocalEntityInfo.getInstantLastUpdate()))
                                            .orElse(false);
                                } else {
                                    return false;
                                }
                            })
                            .toList();
                });
    }

    private boolean isExternalEntityVersionNewer(Float externalEntityVersion, Float sameLocalEntityVersion) {
        return externalEntityVersion > sameLocalEntityVersion;
    }


    private boolean isExternalEntityLastUpdateNewer(Instant externalEntityLastUpdate, Instant
            sameLocalEntityLastUpdate) {
        return externalEntityLastUpdate.isAfter(sameLocalEntityLastUpdate);
    }

    private Mono<DataNegotiationResult> createDataNegotiationResult
            (Mono<String> issuerMono, Mono<List<MVEntity4DataNegotiation>> newEntitiesToSync, Mono<List<MVEntity4DataNegotiation>> existingEntitiesToSync) {
        return Mono.zip(issuerMono, newEntitiesToSync, existingEntitiesToSync).map(
                tuple -> {
                    String issuer = tuple.getT1();
                    List<MVEntity4DataNegotiation> newEntitiesToSyncValue = tuple.getT2();
                    List<MVEntity4DataNegotiation> existingEntitiesToSyncValue = tuple.getT3();

                    return new DataNegotiationResult(issuer, newEntitiesToSyncValue, existingEntitiesToSyncValue);
                }
        );
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