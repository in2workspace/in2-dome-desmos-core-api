package es.in2.desmos.domain.utils;

public final class EndpointsConstants {

    private EndpointsConstants() {
        throw new IllegalStateException("Utility class");
    }

    // Health and Monitoring Endpoints
    public static final String HEALTH = "/health";
    public static final String PROMETHEUS = "/prometheus";

    // Backoffice Endpoints
    public static final String P2P_DATA_SYNC = "/backoffice/v1/actions/sync";

    // Base API Prefix
    public static final String API_V1_PREFIX = "/api/v1";

    // P2P Sync Endpoints
    public static final String P2P_SYNC_PREFIX = API_V1_PREFIX + "/sync/p2p";
    public static final String P2P_DISCOVERY_SYNC = P2P_SYNC_PREFIX + "/discovery";
    public static final String P2P_ENTITIES_SYNC = P2P_SYNC_PREFIX + "/entities";

    // Entity Endpoint
    public static final String GET_ENTITY = API_V1_PREFIX + "/entities";

    // Notifications
    public static final String DLT_ADAPTER_NOTIFICATION = API_V1_PREFIX + "/notifications/dlt";
    public static final String CONTEXT_BROKER_NOTIFICATION = API_V1_PREFIX + "/notifications/broker";
}
