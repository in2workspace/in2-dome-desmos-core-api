package es.in2.desmos.application.workflows.jobs.impl;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonParser;
import es.in2.desmos.application.workflows.jobs.DataPublicationJob;
import es.in2.desmos.application.workflows.jobs.DataTransferJob;
import es.in2.desmos.domain.exceptions.InvalidSyncResponseException;
import es.in2.desmos.domain.models.*;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.domain.services.sync.EntitySyncWebClient;
import es.in2.desmos.domain.utils.ApplicationUtils;
import es.in2.desmos.domain.utils.Base64Converter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataTransferJobImpl implements DataTransferJob {
    public static final String INVALID_ENTITY_SYNC_RESPONSE = "Invalid EntitySync response.";
    private final EntitySyncWebClient entitySyncWebClient;
    private final DataPublicationJob dataPublicationJob;
    private final ObjectMapper objectMapper;
    private final AuditRecordService auditRecordService;

    @Override
    public Mono<Void> syncDataFromList(String processId, Mono<List<DataNegotiationResult>> dataNegotiationResult) {
        return dataNegotiationResult.flatMapIterable(results -> results)
                .flatMap(result -> syncData(processId, Mono.just(result)))
                .collectList()
                .then();
    }

    @Override
    public Mono<Void> syncData(String processId, Mono<DataNegotiationResult> dataNegotiationResult) {
        return dataNegotiationResult.flatMap(result -> {
            log.info("ProcessID: {} - Starting Data Transfer Job", processId);

            log.debug("ProcessID: {} - Issuer: {}", processId, result.issuer());
            log.debug("ProcessID: {} - New Entities to Sync: {}", processId, result.newEntitiesToSync());
            log.debug("ProcessID: {} - Existing Entities to Sync: {}", processId, result.existingEntitiesToSync());

            if (!result.newEntitiesToSync().isEmpty() || !result.existingEntitiesToSync().isEmpty()) {
                Mono<String> issuer = Mono.just(result.issuer());

                Mono<Id[]> entitiesToRequest = buildAllEntitiesToRequest(
                        Mono.just(result.newEntitiesToSync()),
                        Mono.just(result.existingEntitiesToSync())
                );


                return entitiesToRequest.flatMap(entities -> entitySyncWebClient.makeRequest(processId, issuer, entitiesToRequest)
                        .collectList()
                        .flatMap(entitySyncResponse -> {
                            Mono<List<String>> entitySyncResponseMono = Mono.just(entitySyncResponse);

                            return decodeEntitySyncResponse(entitySyncResponseMono)
                                    .flatMap(decodedEntitySyncResponse -> {
                                        Mono<String> decodedEntitySyncResponseMono = Mono.just(decodedEntitySyncResponse);

                                        return getEntitiesById(decodedEntitySyncResponseMono)
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

                                                    return createReceivedAuditRecords(processId, issuer, mvEntities4DataNegotiation)
                                                            .then(getInvalidIntegrityEntitiesIds(processId, entitiesByIdMono, entitiesHashAndHashlinkById))
                                                            .flatMap(invalidIntegrityEntitiesIds -> {
                                                                Mono<List<Id>> invalidIntegrityEntitiesIdsMono = Mono.just(invalidIntegrityEntitiesIds);
                                                                return filterEntitiesById(entitiesByIdMono, invalidIntegrityEntitiesIdsMono)
                                                                        .flatMap(filteredEntitiesById -> {
                                                                            Mono<Map<Id, Entity>> filteredEntitiesByIdMono = Mono.just(filteredEntitiesById);
                                                                            return dataPublicationJob.verifyData(processId, issuer, filteredEntitiesByIdMono, mvEntities4DataNegotiation, existingEntitiesHashAndHashLinkById);

                                                                        });
                                                            });
                                                });
                                    });
                        }));
            } else {
                return Mono.empty();
            }
        });
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

    private Mono<String> decodeEntitySyncResponse(Mono<List<String>> entitySyncResponseMono) {
        return entitySyncResponseMono
                .flatMapIterable(entitySyncResponse -> entitySyncResponse)
                .map(Base64Converter::convertBase64ToString)
                .collectList()
                .flatMap(decodedList -> Mono.just(decodedList)
                        .flatMapIterable(entities -> entities)
                        .map(entity -> JsonParser.parseString(entity).getAsJsonObject())
                        .collectList()
                        .flatMap(jsonObjects -> {
                            try {
                                return Mono.just(objectMapper.readTree(jsonObjects.toString()).toString());
                            } catch (JsonProcessingException e) {
                                return Mono.error(e);
                            }
                        }));
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

    private Mono<Map<Id, Entity>> getEntitiesById(Mono<String> entitiesMono) {
        return entitiesMono.flatMap(entities -> {
            try {
                JsonNode entitiesJsonNode = objectMapper.readTree(entities);
                return Mono.just(entitiesJsonNode)
                        .flatMapIterable(entitiesJsonNodes -> entitiesJsonNodes)
                        .flatMap(entityNode -> {
                            if (entityNode.has("id")) {
                                String currentEntityId = entityNode.get("id").asText();
                                return Mono.just(Map.entry(new Id(currentEntityId), new Entity(entityNode.toString())));
                            } else {
                                return Mono.error(new InvalidSyncResponseException(INVALID_ENTITY_SYNC_RESPONSE));
                            }
                        })
                        .collectMap(Map.Entry::getKey, Map.Entry::getValue);
            } catch (JsonProcessingException e) {
                return Mono.error(e);
            }
        });
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
        return allEntitiesExistingValidationDataById
                .flatMapIterable(Map::entrySet)
                .flatMap(entry -> {
                    Id id = entry.getKey();
                    HashAndHashLink hashAndHashLink = entry.getValue();
                    Mono<String> entityRcvdHash = Mono.just(hashAndHashLink.hash());
                    Mono<String> entityMono = entitiesByIdMono.map(x -> x.get(id).value());
                    return calculateHash(entityMono)
                            .flatMap(calculatedHash ->
                                    entityRcvdHash.flatMap(hashValue -> {
                                        if (calculatedHash.equals(hashValue)) {
                                            return Mono.empty();
                                        } else {
                                            log.warn("ProcessID: {} - P2P replication attempt failed for entity '{}' " +
                                                    "due to hash mismatch: received hash does not match the " +
                                                    "calculated hash of the entity", processId, id);
                                            log.debug("Expected hash: {}\nCurrent hash: {}", hashValue, calculatedHash);
                                            return Mono.just(id);
                                        }
                                    }));
                })
                .collectList();
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
