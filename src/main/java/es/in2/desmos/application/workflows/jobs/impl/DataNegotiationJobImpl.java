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
import java.util.Set;
import java.util.stream.Collectors;

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

        Mono<List<MVEntity4DataNegotiation>> externalMVEntities4DataNegotiationMono = dataNegotiationEvent.externalEntitiesInfo();
        Mono<List<MVEntity4DataNegotiation>> localMVEntities4DataNegotiationMono = dataNegotiationEvent.localEntitiesInfo();

        return getDataNegotiationResultMono(processId, localMVEntities4DataNegotiationMono, dataNegotiationEvent.issuer(), externalMVEntities4DataNegotiationMono)
                .flatMap(dataNegotiationResults -> dataTransferJob.syncData(processId, Mono.just(dataNegotiationResults)));
    }

    private Mono<DataNegotiationResult> getDataNegotiationResultMono(String processId, Mono<List<MVEntity4DataNegotiation>> localMVEntities4DataNegotiationMono, Mono<String> externalIssuerMono, Mono<List<MVEntity4DataNegotiation>> externalMVEntities4DataNegotiation) {
        return externalMVEntities4DataNegotiation
                .flatMapMany(Flux::fromIterable)
                .flatMap(externalMVEntities -> {
                    MVEntityReplicationPoliciesInfo mvEntityReplicationPoliciesInfo =
                            new MVEntityReplicationPoliciesInfo(
                                    externalMVEntities.id(),
                                    externalMVEntities.lifecycleStatus(),
                                    externalMVEntities.startDateTime(),
                                    externalMVEntities.endDateTime());
                    return replicationPoliciesService
                            .isMVEntityReplicable(processId, mvEntityReplicationPoliciesInfo)
                            .flatMap(isReplicable -> {
                                if (Boolean.TRUE.equals(isReplicable)) {
                                    return Mono.just(externalMVEntities);
                                } else {
                                    return Mono.empty();
                                }
                            });
                }).filter(Objects::nonNull)
                .collectList()
                .flatMap(validExternalMvEntities4DataNegotiation -> {
                    Mono<List<MVEntity4DataNegotiation>> externalMVEntities4DataNegotiationMono = Mono.just(validExternalMvEntities4DataNegotiation);
                    return checkWithExternalDataIsMissing(externalMVEntities4DataNegotiationMono, localMVEntities4DataNegotiationMono)
                            .zipWith(checkVersionsAndLastUpdateFromEntityIdMatched(externalMVEntities4DataNegotiationMono, localMVEntities4DataNegotiationMono))
                            .flatMap(tuple -> {
                                List<MVEntity4DataNegotiation> newEntitiesToSync = tuple.getT1();
                                List<MVEntity4DataNegotiation> existingEntitiesToSync = tuple.getT2();

                                log.debug("ProcessID: {} - New entities to sync: {}", processId, newEntitiesToSync);
                                log.debug("ProcessID: {} - Existing entities to sync: {}", processId, existingEntitiesToSync);

                                return externalIssuerMono.
                                        flatMap(externalIssuer -> {
                                            Mono<String> issuerMono = Mono.just(externalIssuer);
                                            return createDataNegotiationResult(issuerMono, Mono.just(newEntitiesToSync), Mono.just(existingEntitiesToSync));
                                        });
                            });

                });
    }

    private Mono<List<MVEntity4DataNegotiation>> checkWithExternalDataIsMissing(
            Mono<List<MVEntity4DataNegotiation>> externalEntityIds,
            Mono<List<MVEntity4DataNegotiation>> localEntityIds) {
        return externalEntityIds.zipWith(localEntityIds)
                .map(tuple -> {

                    List<MVEntity4DataNegotiation> originalList = tuple.getT1();
                    Set<String> idsToCheck = tuple.getT2().stream()
                            .map(MVEntity4DataNegotiation::id)
                            .collect(Collectors.toSet());

                    return originalList.stream()
                            .filter(entity -> !idsToCheck.contains(entity.id()))
                            .toList();
                });
    }


    private Mono<List<MVEntity4DataNegotiation>> checkVersionsAndLastUpdateFromEntityIdMatched(
            Mono<List<MVEntity4DataNegotiation>> externalEntityIds,
            Mono<List<MVEntity4DataNegotiation>> localEntityIds) {
        return externalEntityIds.zipWith(localEntityIds)
                .map(tuple -> {
                    List<MVEntity4DataNegotiation> externalList = tuple.getT1();
                    List<MVEntity4DataNegotiation> localList = tuple.getT2();

                    return externalList
                            .stream()
                            .filter(externalEntity ->
                                    localList
                                            .stream()
                                            .filter(localEntity ->
                                                    localEntity.id().equals(externalEntity.id()) &&
                                                            localEntity.version() != null && !localEntity.version().isBlank() &&
                                                            localEntity.lastUpdate() != null && !localEntity.lastUpdate().isBlank())
                                            .findFirst()
                                            .map(sameLocalEntity ->
                                                    {
                                                        Float externalEntityVersion = externalEntity.getFloatVersion();
                                                        Float sameLocalEntityVersion = sameLocalEntity.getFloatVersion();
                                                        return isExternalEntityVersionNewer(
                                                                externalEntityVersion,
                                                                sameLocalEntityVersion) ||
                                                                (isVersionEqual(
                                                                        externalEntityVersion,
                                                                        sameLocalEntityVersion) &&
                                                                        isExternalEntityLastUpdateNewer(
                                                                                externalEntity.getInstantLastUpdate(),
                                                                                sameLocalEntity.getInstantLastUpdate()));
                                                    }
                                            )
                                            .orElse(false))
                            .toList();
                });
    }

    private boolean isExternalEntityVersionNewer(Float externalEntityVersion, Float sameLocalEntityVersion) {
        return externalEntityVersion > sameLocalEntityVersion;
    }

    private boolean isVersionEqual(Float externalEntityVersion, Float sameLocalEntityVersion) {
        return Objects.equals(externalEntityVersion, sameLocalEntityVersion);
    }

    private boolean isExternalEntityLastUpdateNewer(Instant externalEntityLastUpdate, Instant sameLocalEntityLastUpdate) {
        return externalEntityLastUpdate.isAfter(sameLocalEntityLastUpdate);
    }

    private Mono<DataNegotiationResult> createDataNegotiationResult(Mono<String> issuerMono, Mono<List<MVEntity4DataNegotiation>> newEntitiesToSync, Mono<List<MVEntity4DataNegotiation>> existingEntitiesToSync) {
        return Mono.zip(issuerMono, newEntitiesToSync, existingEntitiesToSync).map(
                tuple -> {
                    String issuer = tuple.getT1();
                    List<MVEntity4DataNegotiation> newEntitiesToSyncValue = tuple.getT2();
                    List<MVEntity4DataNegotiation> existingEntitiesToSyncValue = tuple.getT3();

                    return new DataNegotiationResult(issuer, newEntitiesToSyncValue, existingEntitiesToSyncValue);
                }
        );
    }
}