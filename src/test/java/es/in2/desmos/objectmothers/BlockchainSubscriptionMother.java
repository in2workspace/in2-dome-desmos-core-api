package es.in2.desmos.objectmothers;

import es.in2.desmos.domain.models.BlockchainSubscription;
import es.in2.desmos.domain.utils.ApplicationConstants;

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
                "product-specification",
                "quote");

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
        List<String> metadata = List.of("local");

        String notificationEndpoint = "http://desmos:8080" + dltNotificationEndpoint;
        return new BlockchainSubscription(ApplicationConstants.ROOT_OBJECTS_LIST, metadata, notificationEndpoint);
    }
}
