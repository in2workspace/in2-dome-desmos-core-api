package es.in2.desmos.domain.services.broker.adapter.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.exceptions.JsonReadingException;
import es.in2.desmos.domain.exceptions.RequestErrorException;
import es.in2.desmos.domain.exceptions.SubscriptionCreationException;
import es.in2.desmos.domain.models.BrokerEntityWithIdAndType;
import es.in2.desmos.domain.models.BrokerSubscription;
import es.in2.desmos.domain.services.broker.adapter.BrokerAdapterService;
import es.in2.desmos.infrastructure.configs.BrokerConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static es.in2.desmos.domain.utils.MessageUtils.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScorpioAdapter implements BrokerAdapterService {

    private final ObjectMapper objectMapper;
    private final BrokerConfig brokerConfig;

    private WebClient webClient;

    @PostConstruct
    public void init() {
        this.webClient = WebClient.builder().baseUrl(brokerConfig.getInternalDomain()).build();
    }

    @Override
    public Mono<Void> postEntity(String processId, String requestBody) {
        log.info("ProcessID: {} - Posting entity to Scorpio", processId);
        log.debug("ProcessID: {} - Posting entity to Scorpio: {}", processId, requestBody);
        MediaType mediaType = getContentTypeAndAcceptMediaType(requestBody);
        return webClient.post()
                .uri(brokerConfig.getEntitiesPath())
                .accept(mediaType)
                .contentType(mediaType)
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(
                        status -> status.value() == 409,
                        clientResponse -> {
                            log.info("ProcessID: {} - 409 Conflict from POSTing entity to Scorpio", processId);
                            return Mono.empty();
                        }
                )
                .bodyToMono(Void.class)
                .retry(3);
    }

    @Override
    public Flux<String> getEntitiesByTimeRange(String processId, String timestamp) {
        return webClient.get()
                .uri(brokerConfig.getTemporalPath()
                        + "?timerel=after"
                        + "&timeproperty=createdAt"
                        + "&timeAt="
                        + timestamp
                        + "&attrs")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToFlux(String.class)
                .retry(3);
    }

    @Override
    public Mono<String> getEntityById(String processId, String entityId) {
        return webClient.get()
                .uri(brokerConfig.getEntitiesPath() + "/" + entityId)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(status -> status.isSameCodeAs(HttpStatusCode.valueOf(404)),
                        response -> {
                            log.debug("ProcessId: {}, Entity not found: {}", processId, entityId);
                            return response.bodyToMono(String.class).flatMap(body -> Mono.empty());
                        }
                )
                .onStatus(HttpStatusCode::is5xxServerError,
                        response -> Mono.error(new RequestErrorException("Internal Server Error"))
                )
                .bodyToMono(String.class);
    }

    @Override
    public Mono<Void> updateEntity(String processId, String requestBody) {
        String requestBodyAsArray = "[" + requestBody + "]";
        return extractEntityIdFromRequestBody(processId, requestBody)
                .flatMap(entityId -> {
                    MediaType mediaType = getContentTypeAndAcceptMediaType(requestBody);
                    return webClient.post()
                            .uri(brokerConfig.getEntityOperationsPath() + "/upsert")
                            .accept(mediaType)
                            .contentType(mediaType)
                            .bodyValue(requestBodyAsArray)
                            .retrieve()
                            .bodyToMono(Void.class)
                            .retry(3);
                })
                .doOnSuccess(result -> log.info(RESOURCE_UPDATED_MESSAGE, processId))
                .doOnError(e -> log.error(ERROR_UPDATING_RESOURCE_MESSAGE, e.getMessage()));
    }

    @Override
    public Mono<Void> deleteEntityById(String processId, String entityId) {
        return webClient.delete()
                .uri(brokerConfig.getEntitiesPath() + "/" + entityId)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(Void.class)
                .retry(3);
    }

    @Override
    public Mono<Void> createSubscription(String processId, BrokerSubscription brokerSubscription) {
        // Get all subscriptions from Context Broker
        return getSubscriptions(processId)
                // Validate response and match with use cases
                .flatMap(subscriptionList -> {
                    if (subscriptionList.isEmpty()) {
                        // Use Case: The subscription list is empty.
                        log.debug("ProcessId: {}, Subscription list is empty. Creating new subscription.", processId);
                        return postSubscription(brokerSubscription);
                    } else {
                        // Use Case: The subscription list is not empty.
                        // Check if the subscription you are trying to create already exists in the list comparing the endpoint.
                        Optional<BrokerSubscription> subscriptionItemFound = checkIfSubscriptionExists(brokerSubscription, subscriptionList);

                        if (subscriptionItemFound.isPresent()) {
                            // The subscription you are trying to create has been found in the list with the same
                            // endpoint that you are trying to create.
                            BrokerSubscription subscriptionItem = subscriptionItemFound.get();
                            log.debug("ProcessId: {}, Subscription Entity Found: {}", processId, subscriptionItem);

                            log.debug("ProcessId: {}, Subscription already exists. Checking if it needs to be updated.", processId);
                            if (checkIfBothSubscriptionsHaveTheSameEntityList(subscriptionItem.entities(), brokerSubscription.entities())) {
                                // Use Case: The subscription you are trying to create already exists in the list
                                // and the entities are the same.
                                log.info("ProcessId: {}, Does not need to be created.", processId);
                                return Mono.empty();
                            } else {
                                // Use Case: The subscription you are trying to create already exists in the list
                                // but the endpoint and the entities are different.
                                BrokerSubscription updatedSubscription = BrokerSubscription.builder()
                                        .id(subscriptionItem.id())
                                        .type(brokerSubscription.type())
                                        .entities(brokerSubscription.entities())
                                        .notification(brokerSubscription.notification())
                                        .build();
                                log.info("ProcessId: {}, Updating subscription...", processId);
                                return updateSubscription(updatedSubscription)
                                        .doOnSuccess(result -> log.debug(SUBSCRIPTION_UPDATED_MESSAGE, processId))
                                        .doOnError(e -> log.error(ERROR_UPDATING_SUBSCRIPTION_MESSAGE, processId, e.getMessage()));
                            }
                        } else {
                            log.debug("ProcessId: {}, Subscription Entity Not Found", processId);
                            // Use Case: The subscription you are trying to create does not exist in the list.
                            log.info("ProcessId: {}, Subscription does not exist. Creating new subscription...", processId);
                            return postSubscription(brokerSubscription);
                        }
                    }
                })
                .doOnSuccess(result -> log.debug("ProcessId: {}, Subscription created successfully", processId))
                .doOnError(e -> log.error("ProcessId: {}, Error creating subscription", processId, e))
                .then();
    }

    @Override
    public Mono<List<BrokerSubscription>> getSubscriptions(String processId) {
        return webClient.get()
                .uri(brokerConfig.getSubscriptionsPath())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<BrokerSubscription>>() {
                })
                .onErrorMap(error -> new SubscriptionCreationException("Error fetching subscriptions from broker"));
    }

    @Override
    public Mono<Void> updateSubscription(String processId, BrokerSubscription brokerSubscription) {
        return webClient.patch()
                .uri(brokerConfig.getSubscriptionsPath() + "/" + brokerSubscription.id())
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(brokerSubscription)
                .retrieve()
                .bodyToMono(Void.class);
    }

    @Override
    public Mono<Void> deleteSubscription(String processId, String subscriptionId) {
        return webClient.delete()
                .uri(brokerConfig.getSubscriptionsPath() + "/" + subscriptionId)
                .retrieve()
                .bodyToMono(Void.class);
    }

    @Override
    public <T extends BrokerEntityWithIdAndType> Flux<T> findAllIdTypeAndAttributesByType(String processId, String type, String firstAttribute, String secondAttribute, String thirdAttribute, String forthAttribute, Class<T> responseClass) {
        log.info("ProcessID: {} - Getting Entities With Version And Last Update", processId);

        String uri = brokerConfig.getEntitiesPath() + "/" + String.format("?type=%s&options=keyValues&limit=1000", type);

        return webClient
                .get()
                .uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToFlux(responseClass)
                .retry(3);
    }

    private Mono<Void> postSubscription(BrokerSubscription brokerSubscription) {
        return webClient.post()
                .uri(brokerConfig.getSubscriptionsPath())
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(brokerSubscription)
                .retrieve()
                .bodyToMono(Void.class);
    }

    private Mono<Void> updateSubscription(BrokerSubscription brokerSubscription) {
        return webClient.patch()
                .uri(brokerConfig.getSubscriptionsPath() + "/" + brokerSubscription.id())
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(brokerSubscription)
                .retrieve()
                .bodyToMono(Void.class);
    }

    private Optional<BrokerSubscription> checkIfSubscriptionExists(BrokerSubscription brokerSubscription, List<BrokerSubscription> subscriptionList) {
        log.debug("Broker Subscription: {}", brokerSubscription);
        log.debug("Broker Subscription List: {}", subscriptionList);
        return subscriptionList.stream().findAny().filter(subscriptionItem -> checkIfBothSubscriptionsHaveTheSameEndpointAttribute(subscriptionItem, brokerSubscription));
    }

    private boolean checkIfBothSubscriptionsHaveTheSameEndpointAttribute(BrokerSubscription subscription1, BrokerSubscription subscription2) {
        log.debug("Subscription 1 URI: {}", subscription1.notification().subscriptionEndpoint().uri());
        log.debug("Subscription 2 URI: {}", subscription2.notification().subscriptionEndpoint().uri());
        return Objects.equals(subscription1.notification().subscriptionEndpoint().uri(),
                subscription2.notification().subscriptionEndpoint().uri());
    }

    private boolean checkIfBothSubscriptionsHaveTheSameEntityList(List<BrokerSubscription.Entity> entityList1, List<BrokerSubscription.Entity> entityList2) {
        log.debug("Subscription 1 Entities: {}", entityList1);
        log.debug("Subscription 2 Entities: {}", entityList2);
        return new HashSet<>(entityList1).equals(new HashSet<>(entityList2));
    }

    private MediaType getContentTypeAndAcceptMediaType(String requestBody) {
        try {
            JsonNode jsonNode = objectMapper.readTree(requestBody);
            if (jsonNode.isArray()) {
                jsonNode = jsonNode.get(0);
            }

            if (jsonNode.has("@context")) {
                return MediaType.valueOf("application/ld+json");
            } else {
                return MediaType.APPLICATION_JSON;
            }
        } catch (JsonProcessingException e) {
            throw new JsonReadingException(e.getMessage());
        }
    }

    private Mono<String> extractEntityIdFromRequestBody(String processId, String requestBody) {
        try {
            JsonNode jsonNode = objectMapper.readTree(requestBody);
            if (jsonNode.has("id")) {
                return Mono.just(jsonNode.get("id").asText());
            } else {
                log.error(ENTITY_ID_NOT_FOUND_ERROR_MESSAGE, processId);
                throw new JsonReadingException("Entity ID field not found");
            }
        } catch (Exception e) {
            log.error(READING_JSON_ENTITY_ERROR_MESSAGE, processId, e.getMessage());
            throw new JsonReadingException(e.getMessage());
        }
    }

}
