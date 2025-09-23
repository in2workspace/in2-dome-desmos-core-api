package es.in2.desmos.objectmothers;

import es.in2.desmos.domain.models.BlockchainSubscription;

import java.util.List;

public final class BlockchainSubscriptionMother {

    private BlockchainSubscriptionMother() {
    }

    public static BlockchainSubscription sample(String dltNotificationEndpoint) {
        List<String> eventTypes = List.of(
                "catalog",
                "product-offering",
                "category",
                "individual",
                "organization",
                "product",
                "service-specification",
                "product-offering-price",
                "resource-specification",
                "product-specification");

        List<String> metadata = List.of("dev");

        return new BlockchainSubscription(eventTypes, metadata, dltNotificationEndpoint);
    }

    public static BlockchainSubscription otherEventTypesSubscription(String dltNotificationEndpoint) {
        List<String> eventTypes = List.of(
                "other thing",
                "other event");

        return new BlockchainSubscription(eventTypes, sample(dltNotificationEndpoint).metadata(), sample(dltNotificationEndpoint).notificationEndpoint());
    }

    public static BlockchainSubscription otherNotificationEndpointSubscription( String dltNotificationEndpoint) {
        String notificationEndpoint = "/other/endpoint";

        return new BlockchainSubscription(sample(dltNotificationEndpoint).eventTypes(), sample(dltNotificationEndpoint).metadata(), notificationEndpoint);
    }

    public static BlockchainSubscription defaultConfigured(String dltNotificationEndpoint) {
        List<String> eventTypes = List.of("individual",
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

        List<String> metadata = List.of("local");

        String notificationEndpoint = "http://localhost:8081" + dltNotificationEndpoint;
        return new BlockchainSubscription(eventTypes, metadata, notificationEndpoint);
    }
}
