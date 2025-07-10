package es.in2.desmos.domain.utils;

import java.util.List;

public final class ApplicationConstants {

    public static final String HASH_PREFIX = "0x";
    public static final String HASHLINK_PREFIX = "?hl=";
    public static final String SUBSCRIPTION_ID_PREFIX = "urn:ngsi-ld:Subscription:";
    public static final String SUBSCRIPTION_TYPE = "Subscription";
    public static final String YAML_FILE_SUFFIX = ".yaml";
    public static final List<String> ROOT_OBJECTS_LIST = List.of(
            "catalog");

    private ApplicationConstants() {
        throw new IllegalStateException("Utility class");
    }

}
