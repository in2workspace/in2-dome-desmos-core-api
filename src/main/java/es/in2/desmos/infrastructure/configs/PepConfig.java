package es.in2.desmos.infrastructure.configs;

import es.in2.desmos.infrastructure.configs.properties.PepProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class PepConfig {
    private final PepProperties pepProperties;

    public String getUrl() {
        return pepProperties.externalDomain() + pepProperties.path();
    }
}