package es.in2.desmos.it.domain.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.models.BrokerEntityWithIdTypeLastUpdateAndVersion;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import es.in2.desmos.inflators.ScorpioInflator;
import es.in2.desmos.it.ContainerManager;
import es.in2.desmos.objectmothers.MVBrokerEntity4DataNegotiationMother;
import es.in2.desmos.objectmothers.MVEntity4DataNegotiationMother;
import org.json.JSONException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BrokerPublisherServiceIT {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    BrokerPublisherService brokerPublisherService;

    @LocalServerPort
    private int localServerPort;

    private static List<BrokerEntityWithIdTypeLastUpdateAndVersion> initialEntities;

    @DynamicPropertySource
    static void setDynamicProperties(DynamicPropertyRegistry registry) {
        ContainerManager.postgresqlProperties(registry);
    }

    @BeforeAll
    static void setup() throws JSONException, JsonProcessingException {
        String brokerUrl = ContainerManager.getBaseUriForScorpioA();
        initialEntities = MVBrokerEntity4DataNegotiationMother.randomList(2);
        ScorpioInflator.addInitialEntitiesToContextBroker(brokerUrl, initialEntities);
    }

    @AfterAll
    static void tearDown(){
        String brokerUrl = ContainerManager.getBaseUriForScorpioA();
        List<String> ids = initialEntities.stream().map(BrokerEntityWithIdTypeLastUpdateAndVersion::getId).toList();
        ScorpioInflator.deleteInitialEntitiesFromContextBroker(brokerUrl, ids);
    }


    @Test
    void itShouldReturnEntityIds() {
        String processId = "0";
        var resultFlux = brokerPublisherService.findAllIdTypeAndAttributesByType(processId, MVEntity4DataNegotiationMother.PRODUCT_OFFERING_TYPE_NAME, "lastUpdate", "version", "lifecycleStatus", "validFor", BrokerEntityWithIdTypeLastUpdateAndVersion.class);

        StepVerifier.create(resultFlux.collectList())
                .assertNext(result -> assertThat(result).hasSameElementsAs(initialEntities))
                .verifyComplete();
    }
}
