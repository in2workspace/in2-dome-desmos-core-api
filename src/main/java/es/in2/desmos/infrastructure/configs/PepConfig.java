package es.in2.desmos.infrastructure.configs;

import es.in2.desmos.infrastructure.configs.properties.PepProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class PepConfig {
    private final PepProperties pepProperties;

    public String getUrl() {
        return pepProperties.externalDomain() + pepProperties.path();
    }
}