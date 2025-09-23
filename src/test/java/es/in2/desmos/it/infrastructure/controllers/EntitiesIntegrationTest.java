package es.in2.desmos.it.infrastructure.controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import es.in2.desmos.domain.models.Entity;
import es.in2.desmos.domain.utils.Base64Converter;
import es.in2.desmos.domain.utils.EndpointsConstants;
import es.in2.desmos.inflators.ScorpioInflator;
import es.in2.desmos.it.ContainerManager;
import es.in2.desmos.objectmothers.BrokerDataMother;
import org.json.JSONArray;
import org.json.JSONException;
import org.junit.jupiter.api.*;
import org.skyscreamer.jsonassert.JSONAssert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@WithMockUser
@DirtiesContext

class EntitiesIntegrationTest {

    @DynamicPropertySource
    static void setDynamicProperties(DynamicPropertyRegistry registry) {
        ContainerManager.postgresqlProperties(registry);
    }

    @LocalServerPort
    private int localServerPort;

    @Autowired
    ObjectMapper objectMapper;

    private static String getEntitiesEndpoint;


    private WebTestClient webTestClient;

    private static final String BROKER_ENTITIES_JSON =
            BrokerDataMother.GET_ENTITY_REQUEST_WITH_SUB_ENTITIES_ARRAY_JSON_VARIABLE;

    private static final List<String> brokerEntitiesIds = List.of(
            BrokerDataMother.GET_ENTITY_REQUEST_ENTITY_ID,
            BrokerDataMother.GET_ENTITY_REQUEST_SUBENTITY_1_ID,
            BrokerDataMother.GET_ENTITY_REQUEST_SUBENTITY_2_ID
    );

    @BeforeAll
    static void setup() {
        String brokerUrl = ContainerManager.getBaseUriForScorpioA();
        ScorpioInflator.deleteInitialEntitiesFromContextBroker(brokerUrl, brokerEntitiesIds);
        ScorpioInflator.addEntitiesToBroker(
                brokerUrl,
                BROKER_ENTITIES_JSON);

        getEntitiesEndpoint = "/api/v2"+ EndpointsConstants.GET_ENTITY;
    }

    @BeforeEach
    void setUp() {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + localServerPort)
                .build();
    }

    @AfterAll
    static void tearDown() {
        String brokerUrl = ContainerManager.getBaseUriForScorpioA();
        ScorpioInflator.deleteInitialEntitiesFromContextBroker(brokerUrl, brokerEntitiesIds);
    }

    @Test
    @Disabled
    void test() throws JSONException, JsonProcessingException {
        List<String> expectedBrokerEntities = new ArrayList<>();
        for (int i = 0; i < new JSONArray(BROKER_ENTITIES_JSON).length(); i++) {
            expectedBrokerEntities.add(new JSONArray(BROKER_ENTITIES_JSON).getString(i));
        }

        String expectedEntitiesJson = getJsonNodeFromStringsList(expectedBrokerEntities).toString();

        webTestClient
                .get()
                .uri(getEntitiesEndpoint + "/"+ BrokerDataMother.GET_ENTITY_REQUEST_ENTITY_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Entity.class)
                .consumeWith(response -> {
                    try {
                        String actualEntitiesJson = getJsonNodeFromEntitiesBase64List(
                                Objects.requireNonNull(response.getResponseBody())
                        ).toString();

                        JSONAssert.assertEquals(expectedEntitiesJson, actualEntitiesJson, false);
                    } catch (JsonProcessingException | JSONException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private JsonNode getJsonNodeFromEntitiesBase64List(List<Entity> localEntities) throws JsonProcessingException {
        return getJsonNodeFromStringsList(
                localEntities.stream()
                        .map(entity -> Base64Converter.convertBase64ToString(entity.value()))
                        .toList()
        );
    }

    private JsonNode getJsonNodeFromStringsList(List<String> localEntities) throws JsonProcessingException {
        ArrayNode arrayNode = objectMapper.createArrayNode();
        for (var entity : localEntities) {
            arrayNode.add(objectMapper.readTree(entity));
        }
        return arrayNode;
    }

}
