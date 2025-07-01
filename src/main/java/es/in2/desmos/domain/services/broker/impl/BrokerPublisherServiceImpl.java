package es.in2.desmos.domain.services.broker.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.models.BrokerEntityWithIdAndType;
import es.in2.desmos.domain.models.Entity;
import es.in2.desmos.domain.models.Id;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import es.in2.desmos.domain.services.broker.adapter.BrokerAdapterService;
import es.in2.desmos.domain.services.broker.adapter.factory.BrokerAdapterFactory;
import es.in2.desmos.domain.utils.Base64Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BrokerPublisherServiceImpl implements BrokerPublisherService {

    private static final String VALUE_FIELD_NAME = "value";
    private final BrokerAdapterService brokerAdapterService;

    private final ObjectMapper objectMapper;

    public BrokerPublisherServiceImpl(BrokerAdapterFactory brokerAdapterFactory, ObjectMapper objectMapper) {
        this.brokerAdapterService = brokerAdapterFactory.getBrokerAdapter();
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> publishDataToBroker(String processId, String entityId, String retrievedBrokerEntity) {
        // Get the entity ID from the data location in the blockchain notification.
        // This is used to check if the retrieved entity exists in the local broker or not.
        // If it exists, the entity will be updated, otherwise, it will be created.
        return getEntityById(processId, entityId)
                .switchIfEmpty(Mono.just(""))
                .flatMap(response -> {
                    if (response.isBlank()) {
                        log.info("ProcessID: {} - Entity doesn't exist", processId);
                        // Logic for when the entity does not exist, for example, creating it
                        return postEntity(processId, retrievedBrokerEntity);
                    } else {
                        // Logic for when the entity exists
                        log.info("ProcessId: {} - Entity exists", processId);
                        return updateEntity(processId, retrievedBrokerEntity);
                    }
                });
    }

    @Override
    public <T extends BrokerEntityWithIdAndType> Flux<T> findAllIdTypeAndAttributesByType(String processId, String type, String firstAttribute, String secondAttribute, String thirdAttribute, String forthAttribute, Class<T> responseClassArray) {
        return brokerAdapterService.findAllIdTypeAndAttributesByType(processId, type, firstAttribute, secondAttribute, thirdAttribute, forthAttribute, responseClassArray);
    }

    @Override
    public Mono<List<Entity>> findEntitiesAndItsSubentitiesByIdInBase64(String processId, Mono<List<Id>> idsMono, List<Id> processedEntities){
        return findEntitiesAndItsSubentitiesById(processId, idsMono, processedEntities)
                .doOnSuccess(allEntitiesAndSubEntities ->
                        log.debug("ProcessID: {} - Found local entities and sub-entities in Scorpio. [entities={}]", processId, allEntitiesAndSubEntities))
                .flatMap(items -> {
                    var entities = Base64Converter.convertStringListToBase64List(items);
                    return Flux.fromIterable(entities)
                            .map(Entity::new)
                            .collectList();
                })
                .doOnSuccess(base64Entities ->
                        log.debug("ProcessID: {} - Convert local entities and sub-entities in Scorpio to Base64. [entities={}]", processId, base64Entities));

    }

    @Override
    public Mono<String> getEntityById(String processId, String entityId) {
        return brokerAdapterService.getEntityById(processId, entityId);
    }

    @Override
    public Mono<Void> postEntity(String processId, String requestBody) {
        return brokerAdapterService.postEntity(processId, requestBody);
    }

    private Mono<List<String>> findEntitiesAndItsSubentitiesById(String processId, Mono<List<Id>> idsMono, List<Id> processedEntities) {
        return idsMono.flatMapMany(Flux::fromIterable)
                .flatMap(id -> {
                    if (!processedEntities.contains(id)) {
                        return brokerAdapterService.getEntityById(processId, id.id())
                                .flatMap(entity -> {
                                    processedEntities.add(id);
                                    return getEntityRelationshipIds(Mono.just(entity))
                                            .flatMapMany(Flux::fromIterable)
                                            .flatMap(relatedId -> findEntitiesAndItsSubentitiesById(processId, Mono.just(List.of(relatedId)), processedEntities))
                                            .collectList()
                                            .map(relatedEntities -> {
                                                List<String> resultList = relatedEntities.stream()
                                                        .flatMap(List::stream)
                                                        .collect(Collectors.toList());
                                                resultList.add(entity);
                                                return resultList;
                                            });
                                });
                    } else {
                        return Flux.empty();
                    }
                })
                .collectList()
                .flatMap(listsList -> {
                    List<String> resultList = new ArrayList<>();
                    for (List<String> list : listsList) {
                        resultList.addAll(list);
                    }
                    return Mono.just(resultList);
                });
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
