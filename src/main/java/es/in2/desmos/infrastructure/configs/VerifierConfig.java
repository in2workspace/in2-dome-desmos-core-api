package es.in2.desmos.infrastructure.configs;

import es.in2.desmos.infrastructure.configs.properties.VerifierProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class VerifierConfig {
    public static final String WELL_KNOWN_PATH = "/.well-known/openid-configuration";
    public static final String WELL_KNOWN_CONTENT_TYPE = "Content-Type";
    public static final String WELL_KNOWN_CONTENT_TYPE_URL_ENCODED_FORM = "application/x-www-form-urlencoded";

    private final VerifierProperties verifierProperties;

    public String getExternalDomain() {
        return verifierProperties.externalDomain();
    }

    @Bean
    public String getWellKnownPath() {
        return WELL_KNOWN_PATH;
    }

    @Bean
    public String getWellKnownContentType() {
        return WELL_KNOWN_CONTENT_TYPE;
    }

    @Bean
    public String getWellKnownContentTypeUrlEncodedForm() {
        return WELL_KNOWN_CONTENT_TYPE_URL_ENCODED_FORM;
    }
}
