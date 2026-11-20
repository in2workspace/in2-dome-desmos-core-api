package es.in2.desmos.domain.services.broker.adapter.impl;

import es.in2.desmos.domain.models.BrokerEntityWithIdAndType;
import es.in2.desmos.domain.models.BrokerSubscription;
import es.in2.desmos.domain.services.broker.adapter.BrokerAdapterService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

// TODO: Implement OrionLdAdapter. Until then every operation fails with UnsupportedOperationException
@Component
public class OrionLdAdapter implements BrokerAdapterService {

    @Override
    public Mono<Void> postEntity(String processId, String requestBody) {
        return Mono.error(notImplemented());
    }

    @Override
    public Mono<String> getEntityById(String processId, String entityId) {
        return Mono.error(notImplemented());
    }

    @Override
    public Mono<Void> updateEntity(String processId, String requestBody) {
        return Mono.error(notImplemented());
    }

    @Override
    public Mono<Void> deleteEntityById(String processId, String entityId) {
        return Mono.error(notImplemented());
    }

    @Override
    public Mono<Void> createSubscription(String processId, BrokerSubscription brokerSubscription) {
        return Mono.error(notImplemented());
    }

    @Override
    public Mono<List<BrokerSubscription>> getSubscriptions(String processId) {
        return Mono.error(notImplemented());
    }

    @Override
    public Mono<Void> updateSubscription(String processId, BrokerSubscription brokerSubscription) {
        return Mono.error(notImplemented());
    }

    @Override
    public Mono<Void> deleteSubscription(String processId, String subscriptionId) {
        return Mono.error(notImplemented());
    }

    @Override
    public <T extends BrokerEntityWithIdAndType> Flux<T> findAllIdTypeAndAttributesByType(String processId, String type, String firstAttribute, String secondAttribute, String thirdAttribute, String forthAttribute, Class<T> responseClass) {
        return Flux.error(notImplemented());
    }

    private static UnsupportedOperationException notImplemented() {
        return new UnsupportedOperationException("Orion-LD broker adapter is not implemented");
    }

}
