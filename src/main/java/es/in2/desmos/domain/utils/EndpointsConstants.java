package es.in2.desmos.domain.utils;

public final class EndpointsConstants {

    private EndpointsConstants() {
        throw new IllegalStateException("Utility class");
    }

    // Health and Monitoring Endpoints
    public static final String HEALTH = "/health";
    public static final String PROMETHEUS = "/prometheus";

    // Backoffice Endpoints
    public static final String BACKOFFICE_P2P_DATA_SYNC = "/actions/sync";

    // P2P Sync Endpoints
    public static final String P2P_SYNC_PREFIX =  "/sync/p2p";
    public static final String P2P_DISCOVERY_SYNC = "/discovery";
    public static final String P2P_ENTITIES_SYNC = "/entities";

    // Entity Endpoint
    public static final String GET_ENTITY = "/entities";

    // Notifications
    public static final String DLT_ADAPTER_NOTIFICATION = "/notifications/dlt";
    public static final String CONTEXT_BROKER_NOTIFICATION = "/notifications/broker";
}
