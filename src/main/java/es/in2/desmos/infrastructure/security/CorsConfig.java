package es.in2.desmos.infrastructure.security;

import es.in2.desmos.domain.utils.EndpointsConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Collections;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        CorsConfiguration brokerCorsConfig = new CorsConfiguration();
        setBrokerCorsConfig(brokerCorsConfig);
        source.registerCorsConfiguration(EndpointsConstants.CONTEXT_BROKER_NOTIFICATION, brokerCorsConfig);

        CorsConfiguration dltAdapterCorsConfig = new CorsConfiguration();
        setBrokerCorsConfig(dltAdapterCorsConfig);
        source.registerCorsConfiguration(EndpointsConstants.DLT_ADAPTER_NOTIFICATION, dltAdapterCorsConfig);


        CorsConfiguration githubSyncUrlsCorsConfig = new CorsConfiguration();
        setBrokerCorsConfig(githubSyncUrlsCorsConfig);
        source.registerCorsConfiguration(EndpointsConstants.P2P_SYNC_PREFIX + "/**", githubSyncUrlsCorsConfig);

        CorsConfiguration githubEntitiesUrlsCorsConfig = new CorsConfiguration();
        setBrokerCorsConfig(githubEntitiesUrlsCorsConfig);
        source.registerCorsConfiguration(EndpointsConstants.GET_ENTITY + "/**", githubEntitiesUrlsCorsConfig);

        return source;
    }

    private void setBrokerCorsConfig(CorsConfiguration brokerCorsConfig) {
        brokerCorsConfig.setAllowedOrigins(Collections.emptyList());
        brokerCorsConfig.setAllowedMethods(Collections.emptyList());
        brokerCorsConfig.setAllowedHeaders(Collections.emptyList());
        brokerCorsConfig.setExposedHeaders(Collections.emptyList());
        brokerCorsConfig.setAllowCredentials(false);
        brokerCorsConfig.setMaxAge(8000L);
    }
}
