package es.in2.desmos.domain.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.exceptions.HashLinkException;
import es.in2.desmos.domain.models.BlockchainTxPayload;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.infrastructure.configs.EndpointsConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

import static es.in2.desmos.domain.utils.ApplicationConstants.HASHLINK_PREFIX;
import static es.in2.desmos.domain.utils.ApplicationConstants.HASH_PREFIX;
import static es.in2.desmos.domain.utils.ApplicationUtils.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class BlockchainTxPayloadFactory {

    private final ObjectMapper objectMapper;
    private final ApiConfig apiConfig;
    private final EndpointsConfig endpointsConfig;

    public Mono<BlockchainTxPayload> buildBlockchainTxPayload(String processId, Map<String, Object> dataMap, String previousHashLink) {
        log.debug("ProcessID: {} - Building blockchain data...", processId);
        try {
            String entityId = dataMap.get("id").toString();
            String entityIdHash = HASH_PREFIX + calculateSHA256(entityId);
            String entityType = (String) dataMap.get("type");
            String entityHash = calculateSHA256(objectMapper.writeValueAsString(dataMap));
            String entityHashLink = entityHash.equals(previousHashLink) ? previousHashLink : calculateHashLink(previousHashLink, entityHash);
            String dataLocation = apiConfig.getExternalDomain() + endpointsConfig.getEntitiesEndpoint() + "/" + entityId + HASHLINK_PREFIX + entityHashLink;
            String organizationIdentifier = HASH_PREFIX + apiConfig.organizationIdHash();
            String previousEntityHashLink = HASH_PREFIX + previousHashLink;
            List<String> metadataList = List.of(getEnvironmentMetadata(apiConfig.getCurrentEnvironment()));
            return Mono.just(BlockchainTxPayload.builder()
                    .eventType(entityType)
                    .organizationIdentifier(organizationIdentifier)
                    .entityId(entityIdHash)
                    .previousEntityHashLink(previousEntityHashLink)
                    .dataLocation(dataLocation)
                    .metadata(metadataList)
                    .build());
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            log.warn("ProcessID: {} - Error creating blockchain transaction payload: {}", processId, e.getMessage());
            return Mono.error(new HashLinkException("Error creating blockchain transaction payload"));
        }
    }

    public Mono<String> calculatePreviousHashIfEmpty(String processId, Map<String, Object> dataMap) {
        try {
            log.debug("ProcessID: {} - Calculating previous hash...", processId);
            return Mono.just(calculateSHA256(objectMapper.writeValueAsString(dataMap)));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            log.warn("ProcessID: {} - Error creating previous hash from notification data: {}", processId, e.getMessage());
            return Mono.error(new HashLinkException("Error creating previous hash value from notification data"));
        }
    }

}
