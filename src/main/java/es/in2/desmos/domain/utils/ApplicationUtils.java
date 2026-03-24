package es.in2.desmos.domain.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import es.in2.desmos.domain.exceptions.JsonReadingException;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class ApplicationUtils {

    private ApplicationUtils() {
        throw new IllegalStateException("Utility class");
    }

    public static String generateProcessId() {
        return UUID.randomUUID().toString();
    }

    public static String calculateSHA256(String inputData) throws NoSuchAlgorithmException, JsonProcessingException {
        final ObjectMapper objectMapper = new ObjectMapper();
        MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");

        byte[] result = isValidJSON(inputData, objectMapper) ?
                messageDigest.digest(sortAttributesAlphabetically(inputData, objectMapper).getBytes(StandardCharsets.UTF_8)) :
                messageDigest.digest(inputData.getBytes(StandardCharsets.UTF_8));

        return HexFormat.of().formatHex(result);
    }

    private static boolean isValidJSON(String json, ObjectMapper objectMapper) {
        try {
            objectMapper.readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String calculateHashLink(String previousHash, String entityHash) throws NoSuchAlgorithmException, JsonProcessingException {
        String hashConcatenated = previousHash + entityHash;
        log.debug("Previous Hash 1: {}", previousHash);
        log.debug("Entity Hash 2: {}", entityHash);
        log.debug("Hash Concatenated: {}", hashConcatenated);
        return calculateSHA256(hashConcatenated);
    }

    public static String extractHashLinkFromDataLocation(String dataLocation) {
        return Arrays.stream(dataLocation.split("\\?hl="))
                .skip(1)
                .findFirst()
                .orElseThrow(IllegalArgumentException::new);
    }

    public static String extractEntityIdFromDataLocation(String dataLocation) {
        Pattern pattern = Pattern.compile("entities/(.*?)\\?hl=");
        Matcher matcher = pattern.matcher(dataLocation);
        return Optional.of(matcher)
                .filter(Matcher::find)
                .map(m -> m.group(1))
                .orElseThrow(() -> new IllegalArgumentException("Invalid data location format"));
    }

    public static String extractContextBrokerUrlFromDataLocation(String dataLocation) {
        return Arrays.stream(dataLocation.split("\\?hl="))
                .findFirst()
                .orElseThrow(IllegalArgumentException::new);
    }

    public static String getEnvironmentMetadata(String activeProfile) {

        return switch (activeProfile) {
            case "default" -> "local";
            case "dev" -> "sbx";
            case "test" -> "dev";
            case "prod" -> "prd";
            default -> throw new IllegalArgumentException("Unsupported profile: " + activeProfile);
        };
    }

    private static String sortAttributesAlphabetically(String retrievedBrokerEntity, ObjectMapper objectMapper) throws JsonProcessingException {
        JsonNode retrievedBrokerEntityJson = objectMapper.readTree(retrievedBrokerEntity);
        if (retrievedBrokerEntityJson.isObject()) {
            return sortJsonObject(objectMapper, retrievedBrokerEntityJson);
        } else if (retrievedBrokerEntityJson.isArray()) {
            return sortJsonArray(objectMapper, retrievedBrokerEntityJson);
        } else {
            return objectMapper.writeValueAsString(retrievedBrokerEntityJson);
        }
    }

    private static String sortJsonObject(ObjectMapper objectMapper, JsonNode jsonNode) throws JsonProcessingException {
        TreeMap<String, JsonNode> sortedMap = new TreeMap<>();
        jsonNode.fields().forEachRemaining(entry -> {
            try {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (value.isObject() || value.isArray()) {
                    sortedMap.put(key, objectMapper.readTree(sortAttributesAlphabetically(value.toString(), objectMapper)));
                } else {
                    sortedMap.put(key, value);
                }
            } catch (JsonProcessingException e) {
                throw new JsonReadingException("Error occurred while parsing JSON: " + e.getMessage());
            }
        });

        ObjectNode sortedObjectNode = objectMapper.createObjectNode();
        sortedMap.forEach(sortedObjectNode::set);
        return objectMapper.writeValueAsString(sortedObjectNode);
    }

    private static String sortJsonArray(ObjectMapper objectMapper, JsonNode jsonNode) throws JsonProcessingException {
        if (jsonNode.isArray()) {
            List<JsonNode> nodeList = new ArrayList<>();
            for (JsonNode subNode : jsonNode) {
                if (subNode.isObject()) {
                    String jsonAttributesSortedAlphabetically = sortAttributesAlphabetically(subNode.toString(), objectMapper);
                    JsonNode jsonNodeAttributesSortedAlphabetically = objectMapper.readTree(jsonAttributesSortedAlphabetically);
                    nodeList.add(jsonNodeAttributesSortedAlphabetically);
                } else {
                    nodeList.add(subNode);
                }
            }
            Collections.sort(nodeList, (a, b) -> a.toString().compareTo(b.toString()));

            ArrayNode sortedArrayNode = objectMapper.createArrayNode();
            for (JsonNode node : nodeList) {
                sortedArrayNode.add(node);
            }
            return objectMapper.writeValueAsString(sortedArrayNode);
        } else {
            return objectMapper.writeValueAsString(jsonNode);
        }
    }
}