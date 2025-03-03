package es.in2.desmos.infrastructure.configs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import es.in2.desmos.infrastructure.configs.properties.AccessNodeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class TrustFrameworkConfig {

    private final ApiConfig apiConfig;
    private final AccessNodeProperties accessNodeProperties;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private final Scheduler scheduler = Schedulers.boundedElastic();

    private final AtomicReference<HashSet<String>> dltAddressesRef = new AtomicReference<>();

    @Bean
    public Mono<Void> initialize() {
        log.debug("Retrieving YAML data from the external source repository...");
        // Get the External URL from configuration
        String repoPath = accessNodeProperties.trustedAccessNodesList();

        log.debug("External URL: {}", repoPath);
        // Retrieve YAML data from the External URL

        return getAccessNodesListContent()
                .flatMap(yamlAccessNodesList -> {
                    HashSet<String> dltAddresses = deserializeDltAddress(yamlAccessNodesList);
                    saveDltAddressesRef(Mono.just(dltAddresses));
                    return Mono.empty();
                });
    }

    public HashSet<String> getDltAddresses() {
        HashSet<String> dltAddresses = dltAddressesRef.get();
        if (dltAddresses == null || dltAddresses.isEmpty()) {
            return null;
        } else {
            return dltAddresses;
        }
    }

    private void saveDltAddressesRef(Mono<HashSet<String>> dltAddresses) {
        dltAddresses
                .publishOn(scheduler)
                .subscribe(dltAddressesRef::set);
    }

    private Mono<String> getAccessNodesListContent() {
        try {
            URI trustedAccessNodesListUri = new URI(accessNodeProperties.trustedAccessNodesList());
            return apiConfig
                    .webClient()
                    .get()
                    .uri(trustedAccessNodesListUri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .retry(3);
        } catch (URISyntaxException e) {
            return Mono.error(new RuntimeException(e));
        }
    }

    private static String decimalToHex64(String publicKeyString, int desiredLength) {
        BigInteger publicKeyBigInteger = new BigInteger(publicKeyString);
        StringBuilder hexString = new StringBuilder(publicKeyBigInteger.toString(16));
        while (hexString.length() < desiredLength) {
            hexString.insert(0, "0");
        }

        hexString.insert(0, "0x");
        return hexString.toString();
    }

    private HashSet<String> deserializeDltAddress(String yamlContent) {
        try {
            JsonNode rootNode = yamlMapper.readTree(yamlContent);

            HashSet<String> resultSet = new HashSet<>();

            JsonNode organizations = rootNode.path("organizations");
            if (organizations.isArray()) {
                for (JsonNode organization : organizations) {
                    String dltAddressDecimalString = organization.path("dlt_address").asText();
                    String dltAddress = decimalToHex64(dltAddressDecimalString, 40);
                    resultSet.add(dltAddress);
                }
            }

            return resultSet;

        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
