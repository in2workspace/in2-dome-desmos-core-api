package es.in2.desmos.infrastructure.configs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.util.MimeType;
import org.springframework.web.reactive.config.WebFluxConfigurer;

@Configuration
public class WebFluxConfig implements WebFluxConfigurer {

    @Override
    public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
        configurer.defaultCodecs().jackson2JsonDecoder(
                new Jackson2JsonDecoder(
                        new ObjectMapper(),
                        new MimeType("application", "x-ndjson")
                )
        );

        configurer.defaultCodecs().jackson2JsonEncoder(
                new Jackson2JsonEncoder(
                        new ObjectMapper(),
                        new MimeType("application", "x-ndjson")
                )
        );
    }
}

