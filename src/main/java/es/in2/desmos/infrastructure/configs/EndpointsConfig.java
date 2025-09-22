package es.in2.desmos.infrastructure.configs;

import es.in2.desmos.domain.utils.EndpointsConstants;
import es.in2.desmos.infrastructure.configs.properties.ApiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class EndpointsConfig {

    private final ApiProperties apiProperties;

    public String getApiBase() {
        return "/api/" + apiProperties.version();
    }

    @Bean
    public String defaultPrefix() {
        return getApiBase();
    }

    @Bean
    public String p2pDiscoveryEndpoint() {
        return getApiBase() + EndpointsConstants.P2P_SYNC_PREFIX + EndpointsConstants.P2P_DISCOVERY_SYNC;
    }

    @Bean
    public String p2pEntitiesEndpoint() {
        return getApiBase() + EndpointsConstants.P2P_SYNC_PREFIX + EndpointsConstants.P2P_ENTITIES_SYNC;
    }

    @Bean
    public String p2pSyncEndpoint() {
        return getApiBase() + EndpointsConstants.P2P_SYNC_PREFIX;
    }

    @Bean
    public String getEntitiesEndpoint() {
        return getApiBase() + EndpointsConstants.GET_ENTITY;
    }

    @Bean
    public String backofficeDataSyncEndpoint() {
        return "/backoffice/"+ apiProperties.version() + EndpointsConstants.BACKOFFICE_P2P_DATA_SYNC;
    }

    @Bean
    public String dltNotificationEndpoint() {
        return getApiBase() + EndpointsConstants.DLT_ADAPTER_NOTIFICATION;
    }

    @Bean
    public String brokerNotificationEndpoint() {
        return getApiBase() + EndpointsConstants.CONTEXT_BROKER_NOTIFICATION;
    }
}
