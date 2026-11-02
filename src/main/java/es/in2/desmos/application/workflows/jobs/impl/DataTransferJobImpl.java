package es.in2.desmos.application.workflows.jobs.impl;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.application.workflows.jobs.DataPublicationJob;
import es.in2.desmos.application.workflows.jobs.DataTransferJob;
import es.in2.desmos.domain.exceptions.InvalidSyncResponseException;
import es.in2.desmos.domain.models.*;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.domain.services.sync.EntitySyncWebClient;
import es.in2.desmos.domain.utils.ApplicationUtils;
import es.in2.desmos.domain.utils.Base64Converter;
import es.in2.desmos.domain.utils.LogUtils;
import es.in2.desmos.infrastructure.configs.properties.LoggingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static es.in2.desmos.domain.utils.LogUtils.P2P_SYNC;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataTransferJobImpl implements DataTransferJob {
    public static final String INVALID_ENTITY_SYNC_RESPONSE = "Invalid EntitySync response.";
    private final EntitySyncWebClient entitySyncWebClient;
    private final DataPublicationJob dataPublicationJob;
    private final ObjectMapper objectMapper;
    private final AuditRecordService auditRecordService;
    private final LoggingProperties loggingProperties;

    @Override
    public Mono<Void> syncDataFromList(String processId, Mono<List<DataNegotiationResult>> dataNegotiationResult) {
        return dataNegotiationResult.flatMapIterable(results -> results)
                .flatMap(result -> syncData(processId, Mono.just(result)))
                .collectList()
                .then();
    }

    @Override
    public Mono<Void> syncData(String processId, Mono<DataNegotiationResult> dataNegotiationResult) {
        // During the P2P sync the job puts a P2PSyncTypeMetrics in the context to report per-type counters. Other
        // callers, such as negotiation events, get a throwaway instance.
        return Mono.deferContextual(context -> {
            P2PSyncTypeMetrics metrics = context.getOrDefault(P2PSyncTypeMetrics.CONTEXT_KEY, new P2PSyncTypeMetrics());
            return syncData(processId, dataNegotiationResult, metrics);
        });
    }

    private Mono<Void> syncData(String processId, Mono<DataNegotiationResult> dataNegotiationResult, P2PSyncTypeMetrics metrics) {
        return dataNegotiationResult.flatMap(result -> {
            log.debug("ProcessID: {} - Starting Data Transfer Job", processId);

            log.debug("ProcessID: {} - Issuer: {}", processId, result.issuer());
            log.debug("ProcessID: {} - New Entities to Sync: {}", processId, result.newEntitiesToSync());
            log.debug("ProcessID: {} - Existing Entities to Sync: {}", processId, result.existingEntitiesToSync());

            if (!result.newEntitiesToSync().isEmpty() || !result.existingEntitiesToSync().isEmpty()) {
                Mono<String> issuer = Mono.just(result.issuer());

                Mono<Id[]> entitiesToRequest = buildAllEntitiesToRequest(
                        Mono.just(result.newEntitiesToSync()),
                        Mono.just(result.existingEntitiesToSync())
                );


                return entitiesToRequest.flatMap(entities -> getEntitiesById(entitySyncWebClient.makeRequest(processId, issuer, entitiesToRequest))
                        .elapsed()
                        .map(timedEntitiesById -> {
                            Map<Id, Entity> entitiesById = timedEntitiesById.getT2();
                            logEntitySyncResponse(processId, result.issuer(), entities.length, entitiesById.size(),
                                    Duration.ofMillis(timedEntitiesById.getT1()));
                            recordFetchedEntities(processId, result.issuer(), entities, entitiesById.keySet(), metrics);
                            return entitiesById;
                        })
                        .flatMap(entitiesById -> {
                            Mono<Map<Id, Entity>> entitiesByIdMono = Mono.just(entitiesById);

                            Mono<Map<Id, HashAndHashLink>> newEntitiesHashAndHashLinkById = getEntitiesHashAndHashLinkById(Mono.just(result.newEntitiesToSync()));
                            Mono<Map<Id, HashAndHashLink>> existingEntitiesHashAndHashLinkById = getEntitiesHashAndHashLinkById(Mono.just(result.existingEntitiesToSync()));

                            Mono<Map<Id, HashAndHashLink>> entitiesHashAndHashlinkById =
                                    concatTwoEntitiesOriginalValidationDataByIdMaps(
                                            newEntitiesHashAndHashLinkById,
                                            existingEntitiesHashAndHashLinkById);

                            Mono<List<MVEntity4DataNegotiation>> mvEntities4DataNegotiation = buildAllMVEntities4DataNegotiation(
                                    Mono.just(result.newEntitiesToSync()),
                                    Mono.just(result.existingEntitiesToSync())
                            );

                            // The remote may not return every requested entity (e.g. it was deleted or
                            // changed after the discovery). Those are skipped: no RECEIVED audit record,
                            // no integrity check, not stored; the next synchronization will retry them.
                            // The received list is computed once here and is the only one passed on.
                            Mono<List<MVEntity4DataNegotiation>> receivedMvEntities4DataNegotiation =
                                    mvEntities4DataNegotiation.map(mvEntities -> keepReceived(mvEntities, entitiesById.keySet()))
                                            .cache();

                            return createReceivedAuditRecords(processId, issuer, receivedMvEntities4DataNegotiation)
                                    .then(getInvalidIntegrityEntitiesIds(processId, entitiesByIdMono, entitiesHashAndHashlinkById))
                                    .flatMap(invalidIntegrityEntitiesIds -> {
                                        metrics.addInvalidIntegrity(invalidIntegrityEntitiesIds.size());
                                        Mono<List<Id>> invalidIntegrityEntitiesIdsMono = Mono.just(invalidIntegrityEntitiesIds);
                                        return filterEntitiesById(entitiesByIdMono, invalidIntegrityEntitiesIdsMono)
                                                .flatMap(filteredEntitiesById -> {
                                                    Mono<Map<Id, Entity>> filteredEntitiesByIdMono = Mono.just(filteredEntitiesById);
                                                    return dataPublicationJob.verifyData(processId, issuer, filteredEntitiesByIdMono, receivedMvEntities4DataNegotiation, existingEntitiesHashAndHashLinkById);

                                                });
                                    });
                        }));
            } else {
                return Mono.empty();
            }
        });
    }

    // Counted by id, not by size: the remote node answers with the requested entities plus their subentities,
    // and it may leave out some requested ones, which a size difference would hide.
    private static void recordFetchedEntities(String processId, String issuer, Id[] requested, Set<Id> fetchedIds,
                                              P2PSyncTypeMetrics metrics) {
        Set<Id> requestedIds = new HashSet<>(Arrays.asList(requested));
        List<Id> missingIds = requestedIds.stream().filter(id -> !fetchedIds.contains(id)).toList();
        // fetched= counts only the requested entities; the subentities the response also carries go to subentities=
        metrics.addFetched((long) requestedIds.size() - missingIds.size());
        metrics.addSubentities(fetchedIds.stream().filter(id -> !requestedIds.contains(id)).count());
        metrics.addMissing(missingIds.size());
        if (!missingIds.isEmpty()) {
            log.warn("{} ProcessID: {} - genesis {} did not return {} of {} requested entities, they are skipped: {}",
                    P2P_SYNC, processId, issuer, missingIds.size(), requestedIds.size(), missingIds);
        }
    }

    private void logEntitySyncResponse(String processId, String issuer, int requested, int fetched, Duration elapsed) {
        log.debug("{} ProcessID: {} - entities genesis={} requested={} fetched={} {}",
                P2P_SYNC, processId, issuer, requested, fetched, LogUtils.formatDuration(elapsed));
        Duration threshold = loggingProperties.slowRequestThreshold();
        if (elapsed.compareTo(threshold) > 0) {
            log.warn("{} ProcessID: {} - Slow request to genesis {}: entities ({} ids) took {} (threshold {})",
                    P2P_SYNC, processId, issuer, requested, LogUtils.formatDuration(elapsed), LogUtils.formatDuration(threshold));
        }
    }

    // The entities missing from the response are reported (WARN, missing= counter) by recordFetchedEntities
    private static List<MVEntity4DataNegotiation> keepReceived(List<MVEntity4DataNegotiation> requested, Set<Id> receivedIds) {
        return requested.stream()
                .filter(entity -> receivedIds.contains(new Id(entity.id())))
                .toList();
    }

    private Mono<Void> createReceivedAuditRecords(String processId, Mono<String> issuerMono, Mono<List<MVEntity4DataNegotiation>> mvEntities4DataNegotiation) {
        return mvEntities4DataNegotiation
                .flatMapIterable(x -> x)
                .concatMap(x -> {
                    var mvAuditService = new MVAuditServiceEntity4DataNegotiation(
                            x.id(),
                            x.type(),
                            "",
                            "");

                    return issuerMono.flatMap(issuer ->
                            auditRecordService
                                    .buildAndSaveAuditRecordFromDataSync(
                                            processId,
                                            issuer,
                                            mvAuditService,
                                            AuditRecordStatus.RECEIVED));
                })
                .collectList()
                .then();
    }

    private Mono<Id[]> buildAllEntitiesToRequest(Mono<List<MVEntity4DataNegotiation>> newEntitiesToSyncMono, Mono<List<MVEntity4DataNegotiation>> existingEntitiesToSyncMono) {
        return newEntitiesToSyncMono.zipWith(existingEntitiesToSyncMono)
                .flatMap(tuple ->
                        Mono.just(Stream.concat(tuple.getT1().stream().map(x -> new Id(x.id())), tuple.getT2().stream().map(x -> new Id(x.id()))).toArray(Id[]::new)));
    }

    private Mono<List<MVEntity4DataNegotiation>> buildAllMVEntities4DataNegotiation(Mono<List<MVEntity4DataNegotiation>> newEntitiesToSyncMono, Mono<List<MVEntity4DataNegotiation>> existingEntitiesToSyncMono) {
        return newEntitiesToSyncMono.zipWith(existingEntitiesToSyncMono)
                .flatMap(tuple ->
                        Mono.just(Stream.concat(tuple.getT1().stream(), tuple.getT2().stream()).toList()));
    }

    // Each entity is decoded and parsed on its own as it arrives: the whole response is never
    // held as a single string, which ran genesis out of heap on large synchronizations.
    private Mono<Map<Id, Entity>> getEntitiesById(Flux<String> entitySyncResponse) {
        return entitySyncResponse
                .concatMap(this::toEntityById)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private Mono<Map.Entry<Id, Entity>> toEntityById(String base64Entity) {
        try {
            JsonNode entityNode = objectMapper.readTree(Base64Converter.convertBase64ToString(base64Entity));
            if (entityNode.has("id")) {
                String currentEntityId = entityNode.get("id").asText();
                return Mono.just(Map.entry(new Id(currentEntityId), new Entity(entityNode.toString())));
            } else {
                return Mono.error(new InvalidSyncResponseException(INVALID_ENTITY_SYNC_RESPONSE));
            }
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }

    private Mono<Map<Id, HashAndHashLink>> getEntitiesHashAndHashLinkById(Mono<List<MVEntity4DataNegotiation>> allEntitiesToValidate) {
        return allEntitiesToValidate.map(mvEntity4DataNegotiationsList -> {
            Map<Id, HashAndHashLink> entitiesHashAndHashLinkById = new HashMap<>(mvEntity4DataNegotiationsList.size());
            for (MVEntity4DataNegotiation mvEntity4DataNegotiation : mvEntity4DataNegotiationsList) {
                entitiesHashAndHashLinkById.put(new Id(mvEntity4DataNegotiation.id()), new HashAndHashLink(mvEntity4DataNegotiation.hash(), mvEntity4DataNegotiation.hashlink()));
            }
            return entitiesHashAndHashLinkById;
        });
    }

    private Mono<Map<Id, HashAndHashLink>> concatTwoEntitiesOriginalValidationDataByIdMaps
            (Mono<Map<Id, HashAndHashLink>> newEntitiesOriginalValidationDataById, Mono<Map<Id, HashAndHashLink>> existingEntitiesOriginalValidationDataById) {
        return newEntitiesOriginalValidationDataById
                .zipWith(existingEntitiesOriginalValidationDataById)
                .flatMap(tuple -> {
                    Map<Id, HashAndHashLink> allEntitiesOriginalDataValidation = new HashMap<>();
                    allEntitiesOriginalDataValidation.putAll(tuple.getT1());
                    allEntitiesOriginalDataValidation.putAll(tuple.getT2());
                    return Mono.just(allEntitiesOriginalDataValidation);
                });
    }

    private Mono<List<Id>> getInvalidIntegrityEntitiesIds(String processId, Mono<Map<Id, Entity>> entitiesByIdMono, Mono<Map<Id, HashAndHashLink>> allEntitiesExistingValidationDataById) {
        // Only received entities are checked (the same set keepReceived keeps: the keys of entitiesById)
        return entitiesByIdMono.flatMap(entitiesById -> allEntitiesExistingValidationDataById
                .flatMapIterable(Map::entrySet)
                .filter(entry -> entitiesById.containsKey(entry.getKey()))
                .flatMap(entry -> {
                    Id id = entry.getKey();
                    HashAndHashLink hashAndHashLink = entry.getValue();
                    Mono<String> entityRcvdHash = Mono.just(hashAndHashLink.hash());
                    Mono<String> entityMono = Mono.just(entitiesById.get(id).value());
                    return calculateHash(entityMono)
                            .flatMap(calculatedHash ->
                                    entityRcvdHash.flatMap(hashValue -> {
                                        if (calculatedHash.equals(hashValue)) {
                                            return Mono.empty();
                                        } else {
                                            log.warn("ProcessID: {} - P2P replication attempt failed for entity '{}' due to hash mismatch", processId, id);
                                            log.debug("ProcessID: {} - Entity '{}' hash mismatch - Expected: {}, Calculated: {}", processId, id, hashValue, calculatedHash);
                                            return Mono.just(id);
                                        }
                                    }));
                })
                .collectList());
    }

    private Mono<String> calculateHash(Mono<String> retrievedBrokerEntityMono) {
        return retrievedBrokerEntityMono.flatMap(sortedAttributesBrokerEntity -> {
            try {
                return Mono.just(ApplicationUtils.calculateSHA256(sortedAttributesBrokerEntity));
            } catch (NoSuchAlgorithmException | JsonProcessingException e) {
                return Mono.error(e);
            }
        });
    }

    private Mono<Map<Id, Entity>> filterEntitiesById(Mono<Map<Id, Entity>> entitiesByIdMono, Mono<List<Id>> invalidIntegrityEntitiesIdsMono) {
        return invalidIntegrityEntitiesIdsMono.flatMap(invalidIntegrityEntitiesIds ->
                entitiesByIdMono.map(entitiesById ->
                        entitiesById.entrySet()
                                .stream()
                                .filter(entry -> !invalidIntegrityEntitiesIds.contains(entry.getKey()))
                                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
                )
        );
    }
}
