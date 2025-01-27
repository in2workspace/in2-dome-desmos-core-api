package es.in2.desmos.infrastructure.security;

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
        source.registerCorsConfiguration("/api/v1/notifications/broker", brokerCorsConfig);

        CorsConfiguration dltAdapterCorsConfig = new CorsConfiguration();
        setBrokerCorsConfig(dltAdapterCorsConfig);
        source.registerCorsConfiguration("/api/v1/notifications/dlt", dltAdapterCorsConfig);


        CorsConfiguration githubSyncUrlsCorsConfig = new CorsConfiguration();
        setBrokerCorsConfig(githubSyncUrlsCorsConfig);
        source.registerCorsConfiguration("/api/v1/sync/p2p/**", githubSyncUrlsCorsConfig);

        CorsConfiguration githubEntitiesUrlsCorsConfig = new CorsConfiguration();
        setBrokerCorsConfig(githubEntitiesUrlsCorsConfig);
        source.registerCorsConfiguration("/api/v1/entities/**", githubEntitiesUrlsCorsConfig);

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
