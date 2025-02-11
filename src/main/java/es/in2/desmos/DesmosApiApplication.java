package es.in2.desmos;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.Getter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class DesmosApiApplication {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .build();

    @Getter
    private static ConfigurableApplicationContext context;

    public static void main(String[] args) {
        context = SpringApplication.run(DesmosApiApplication.class, args);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return OBJECT_MAPPER;
    }


}