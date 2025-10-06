package es.in2.desmos.inflators;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.models.BrokerEntityWithIdTypeLastUpdateAndVersion;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

public final class ScorpioInflator {

    private static final Logger log = LoggerFactory.getLogger(ScorpioInflator.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private ScorpioInflator() {
    }

    private static final MediaType APPLICATION_LD_JSON = new MediaType("application", "ld+json");

    public static void addInitialEntitiesToContextBroker(String brokerUrl, List<BrokerEntityWithIdTypeLastUpdateAndVersion> initialEntities) throws JSONException, JsonProcessingException {
        String requestBody = createInitialEntitiesRequestBody(initialEntities);

        var result = WebClient.builder()
                .baseUrl(brokerUrl)
                .build()
                .post()
                .uri("ngsi-ld/v1/entityOperations/create")
                .contentType(APPLICATION_LD_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .retry(3).block();

        log.debug("Create entities to Scorpio result: {}", result);
    }

    public static void addEntitiesToBroker(String brokerUrl, String brokerEntities) {
        var result = WebClient.builder()
                .baseUrl(brokerUrl)
                .build()
                .post()
                .uri("ngsi-ld/v1/entityOperations/create")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(brokerEntities)
                .retrieve()
                .bodyToMono(String.class)
                .retry(3).block();

        log.debug("Create entities to Scorpio result: {}", result);
    }

    public static void addInitialEntitiesToContextBroker(String brokerUrl, String requestBody) {
        var result = WebClient.builder()
                .baseUrl(brokerUrl)
                .build()
                .post()
                .uri("ngsi-ld/v1/entityOperations/create")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .retry(3).block();

        log.debug("Create entities to Scorpio result: {}", result);
    }

    public static void addInitialJsonEntitiesToContextBroker(String brokerUrl, String requestBody) {
        var result = WebClient.builder()
                .baseUrl(brokerUrl)
                .build()
                .post()
                .uri("ngsi-ld/v1/entityOperations/create")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .retry(3).block();

        log.debug("Create entities to Scorpio: {}", result);
    }

    public static void deleteInitialEntitiesFromContextBroker(String brokerUrl, List<String> ids) {
        WebClient.builder()
                .baseUrl(brokerUrl)
                .build()
                .post()
                .uri("ngsi-ld/v1/entityOperations/delete")
                .contentType(APPLICATION_LD_JSON)
                .bodyValue(ids)
                .retrieve()
                .bodyToMono(Void.class)
                .retry(3).block();

        log.debug("Remove entities from Scorpio.");
    }

    @NotNull
    private static String createInitialEntitiesRequestBody(List<BrokerEntityWithIdTypeLastUpdateAndVersion> initialEntities) throws JsonProcessingException, JSONException {
        JSONArray productOfferingsJsonArray = new JSONArray();

        for (BrokerEntityWithIdTypeLastUpdateAndVersion productOffering : initialEntities) {
            String productOfferingJsonText = objectMapper.writeValueAsString(productOffering);
            JSONObject productOfferingJson = new JSONObject(productOfferingJsonText);

            JSONArray contextValueFakeList = new JSONArray();
            contextValueFakeList.put("https://uri.etsi.org/ngsi-ld/v1/ngsi-ld-core-context.jsonld");
            productOfferingJson.put("@context", contextValueFakeList);

            productOfferingsJsonArray.put(productOfferingJson);
        }

        String requestBody = productOfferingsJsonArray.toString();
        requestBody = requestBody.replace("\\/", "/");
        return requestBody;
    }
}
