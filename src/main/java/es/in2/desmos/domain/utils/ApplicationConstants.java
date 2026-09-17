package es.in2.desmos.domain.utils;

import java.util.List;
import java.util.Set;

public final class ApplicationConstants {

    public static final String HASH_PREFIX = "0x";
    public static final String HASHLINK_PREFIX = "?hl=";
    public static final String SUBSCRIPTION_ID_PREFIX = "urn:ngsi-ld:Subscription:";
    public static final String SUBSCRIPTION_TYPE = "Subscription";
    public static final String PRODUCT_ORDER = "product-order";
    public static final String QUOTE = "quote";
    public static final String USAGE_SPECIFICATION = "usageSpecification";
    private static final String CUSTOMER_BILL = "customer-bill";
    private static final String APPLIED_CUSTOMER_BILL_RATE = "applied-customer-bill-rate";
    private static final String PRODUCT = "product";
    public static final Set<String> LIFECYCLE_STATUS_FREE_TYPES = Set.of
            (PRODUCT_ORDER,
                    QUOTE,
                    USAGE_SPECIFICATION,
                    CUSTOMER_BILL,
                    APPLIED_CUSTOMER_BILL_RATE,
                    PRODUCT);
    public static final List<String> ROOT_OBJECTS_LIST = List.of("individual",
            "organization",
            "catalog",
            "product-offering",
            "product-offering-price",
            "product-specification",
            "service-specification",
            "resource-specification",
            "category",
            PRODUCT_ORDER,
            PRODUCT,
            "usage",
            USAGE_SPECIFICATION,
            APPLIED_CUSTOMER_BILL_RATE,
            CUSTOMER_BILL,
            QUOTE);

    private ApplicationConstants() {
        throw new IllegalStateException("Utility class");
    }

}
