package es.in2.desmos.infrastructure.configs;

import es.in2.desmos.infrastructure.configs.properties.TxSubscriptionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class BlockchainConfig {

    private static final List<String> ENTITY_TYPES = List.of("individual",
            "organization",
            "catalog",
            "product-offering",
            "product-offering-price",
            "product-specification",
            "service-specification",
            "resource-specification",
            "category",
            "product-order",
            "product",
            "usage",
            "usage-specification",
            "applied-customer-bill-rate",
            "customer-bill");

    private final TxSubscriptionProperties txSubscriptionProperties;

    public String getNotificationEndpoint() {
        return txSubscriptionProperties.notificationEndpoint();
    }

    public List<String> getEntityTypes() {
        return ENTITY_TYPES;
    }

}
