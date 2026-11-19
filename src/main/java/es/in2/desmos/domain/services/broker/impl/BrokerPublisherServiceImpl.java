package es.in2.desmos.domain.services.broker.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.models.BrokerEntityWithIdAndType;
import es.in2.desmos.domain.models.BrokerWriteAction;
import es.in2.desmos.domain.models.Entity;
import es.in2.desmos.domain.models.Id;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import es.in2.desmos.domain.services.broker.adapter.BrokerAdapterService;
import es.in2.desmos.domain.services.broker.adapter.factory.BrokerAdapterFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class BrokerPublisherServiceImpl implements BrokerPublisherService {

    private static final String VALUE_FIELD_NAME = "value";
    private static final int BROKER_READ_CONCURRENCY = 16;
    private final BrokerAdapterService brokerAdapterService;

    private final ObjectMapper objectMapper;

    public BrokerPublisherServiceImpl(BrokerAdapterFactory brokerAdapterFactory, ObjectMapper objectMapper) {
        this.brokerAdapterService = brokerAdapterFactory.getBrokerAdapter();
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> publishDataToBroker(String processId, String entityId, String retrievedBrokerEntity) {
        return upsertDataInBroker(processId, entityId, retrievedBrokerEntity).then();
    }

    // Logged at DEBUG: the callers log the outcome with the entity (e.g. the [subscribe] line)
    @Override
    public Mono<BrokerWriteAction> upsertDataInBroker(String processId, String entityId, String retrievedBrokerEntity) {
        // Check if the retrieved entity exists in the local broker or not.
        // If it exists, the entity will be updated, otherwise, it will be created.
        return getEntityById(processId, entityId)
                .switchIfEmpty(Mono.just(""))
                .flatMap(response -> {
                    if (response.isBlank()) {
                        log.debug("ProcessID: {} - Entity {} doesn't exist, creating it", processId, entityId);
                        return postEntity(processId, retrievedBrokerEntity).thenReturn(BrokerWriteAction.CREATED);
                    } else {
                        log.debug("ProcessID: {} - Entity {} exists, updating it", processId, entityId);
                        return updateEntity(processId, retrievedBrokerEntity).thenReturn(BrokerWriteAction.UPDATED);
                    }
                });
    }

    @Override
    public <T extends BrokerEntityWithIdAndType> Flux<T> findAllIdTypeAndAttributesByType(String processId, String type, String firstAttribute, String secondAttribute, String thirdAttribute, String forthAttribute, Class<T> responseClassArray) {
        return brokerAdapterService.findAllIdTypeAndAttributesByType(processId, type, firstAttribute, secondAttribute, thirdAttribute, forthAttribute, responseClassArray);
    }

    @Override
    public Flux<Entity> findEntitiesAndItsSubentitiesByIdInBase64(String processId, Mono<List<Id>> idsMono) {
        return findEntitiesAndItsSubentitiesById(processId, idsMono)
                .doOnNext(entity ->
                        log.trace("ProcessID: {} - Found local entity in Scorpio. [entity={}]", processId, entity))
                .map(item -> new Entity(Base64.getEncoder().encodeToString(item.getBytes())))
                .doOnNext(base64Entity ->
                        log.trace("ProcessID: {} - Converted entity to Base64. [entity={}]", processId, base64Entity));
    }

    @Override
    public Mono<String> getEntityById(String processId, String entityId) {
        return brokerAdapterService.getEntityById(processId, entityId);
    }

    @Override
    public Mono<Void> postEntity(String processId, String requestBody) {
        return brokerAdapterService.postEntity(processId, requestBody);
    }

    /**
     * Reads the requested entities and, recursively, their subentities, each one once.
     * <p>The requested entities are read {@value #BROKER_READ_CONCURRENCY} at a time and the subentities of each one
     * in sequence, so a request never has more than {@value #BROKER_READ_CONCURRENCY} reads in flight. Without a bound
     * a request with thousands of related entities filled the pending queue of the broker connection pool and failed
     * whole.
     * <p>Within the tree of one requested entity, each subentity is emitted before the entity that references it. A
     * subentity shared by several requested entities is emitted once, in the first tree that reaches it, so it can
     * come after another requested entity that also references it.
     */
    private Flux<String> findEntitiesAndItsSubentitiesById(String processId, Mono<List<Id>> idsMono) {
        // One set per subscription, so a resubscription reads everything again
        return Flux.defer(() -> {
            Set<Id> processedEntities = ConcurrentHashMap.newKeySet();
            return idsMono.flatMapMany(Flux::fromIterable)
                    .flatMap(id -> findEntityAndItsSubentities(processId, id, processedEntities), BROKER_READ_CONCURRENCY);
        });
    }

    private Flux<String> findEntityAndItsSubentities(String processId, Id id, Set<Id> processedEntities) {
        // Atomic check and mark: two entities that share a subentity no longer read and emit it twice
        if (!processedEntities.add(id)) {
            return Flux.empty();
        }
        return brokerAdapterService.getEntityById(processId, id.id())
                .flatMapMany(entity -> getEntityRelationshipIds(Mono.just(entity))
                        .flatMapMany(Flux::fromIterable)
                        .concatMap(relatedId -> findEntityAndItsSubentities(processId, relatedId, processedEntities))
                        .concatWith(Mono.just(entity)));
    }

    private Mono<List<Id>> getEntityRelationshipIds(Mono<String> entityMono) {
        return entityMono.flatMap(entity -> {
            try {
                JsonNode rootEntityJsonNode = objectMapper.readTree(entity);

                return Flux.fromIterable(rootEntityJsonNode::fields)
                        .flatMap(rootEntityNodeField -> {
                            JsonNode rootEntityNodeFieldValue = rootEntityNodeField.getValue();

                            String typeFieldName = "type";
                            if (rootEntityNodeFieldValue.isObject() &&
                                    rootEntityNodeFieldValue.has(typeFieldName)) {
                                String fieldType = rootEntityNodeFieldValue.get(typeFieldName).asText();

                                String relationshipFieldName = "Relationship";
                                String objectFieldName = "object";
                                String propertyFieldName = "Property";
                                if (fieldType.equals(relationshipFieldName) && rootEntityNodeFieldValue.has(objectFieldName)) {
                                    return Mono.just(new Id(rootEntityNodeFieldValue.get(objectFieldName).asText()));
                                } else if (fieldType.equals(propertyFieldName) && rootEntityNodeFieldValue.has(VALUE_FIELD_NAME)) {
                                    var jsonArray = rootEntityNodeFieldValue.get(VALUE_FIELD_NAME);
                                    return Flux.fromIterable(jsonArray)
                                            .flatMap(arrayElement -> getEntityRelationshipIdsFromArray(Mono.just(arrayElement.toString())));
                                }
                            } else if (rootEntityNodeFieldValue.isArray()) {
                                return Flux.fromIterable(rootEntityNodeFieldValue)
                                        .flatMap(arrayElement -> getEntityRelationshipIdsFromArray(Mono.just(arrayElement.toString())));
                            }
                            return Mono.empty();
                        })
                        .collectList();
            } catch (JsonProcessingException e) {
                return Mono.error(e);
            }
        });
    }

    private Mono<Id> getEntityRelationshipIdsFromArray(Mono<String> entityMono) {
        return entityMono.flatMap(entity -> {
            try {
                JsonNode rootEntityJsonNode = objectMapper.readTree(entity);

                String typeFieldName = "type";

                if (rootEntityJsonNode.isObject() &&
                        rootEntityJsonNode.has(typeFieldName)) {

                    String fieldType = rootEntityJsonNode.get(typeFieldName).asText();

                    String relationshipFieldName = "Relationship";
                    String objectFieldName = "object";
                    if (fieldType.equals(relationshipFieldName) && rootEntityJsonNode.has(objectFieldName)) {
                        return Mono.just(new Id(rootEntityJsonNode.get(objectFieldName).asText()));
                    }
                }

                return Mono.empty();


            } catch (JsonProcessingException e) {
                return Mono.error(e);
            }
        });
    }

    private Mono<Void> updateEntity(String processId, String requestBody) {
        return brokerAdapterService.updateEntity(processId, requestBody);
    }

}
