package es.in2.desmos.behaviour.replication;

import es.in2.desmos.domain.utils.EndpointsConstants;
import es.in2.desmos.inflators.ScorpioInflator;
import es.in2.desmos.infrastructure.security.M2MAccessTokenProvider;
import es.in2.desmos.support.ContainerManager;
import es.in2.desmos.objectmothers.BrokerDataMother;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the JWT auth chain end to end, using the exact same setup ({@link ContainerManager}'s
 * shared verifier, the fixed, host-exposed node A port, the DNS-rewriting WebClient) the
 * two-node replication tests depend on -- deliberately validated here, against a single node,
 * before any second (containerized) node's startup can be confused with an auth wiring
 * problem. Exercises both directions: outbound ({@link M2MAccessTokenProvider} builds a client
 * assertion, POSTs it to the stub's token endpoint, gets back a real access token) and inbound
 * (that token authenticates a real HTTP call to node A's own JWT-protected
 * {@code /api/v2/entities/{id}}, verified through the actual SecurityConfig filter chain --
 * no {@code @WithMockUser} shortcut, which only works for same-JVM calls).
 */
class VerifierAuthChainBehaviorTest extends AbstractReplicationBehaviorTest {

    private static final String BROKER_ENTITIES_JSON =
            BrokerDataMother.GET_ENTITY_REQUEST_WITH_SUB_ENTITIES_ARRAY_JSON_VARIABLE;

    private static final List<String> brokerEntityIds = List.of(
            BrokerDataMother.GET_ENTITY_REQUEST_ENTITY_ID,
            BrokerDataMother.GET_ENTITY_REQUEST_SUBENTITY_1_ID,
            BrokerDataMother.GET_ENTITY_REQUEST_SUBENTITY_2_ID
    );

    @Autowired
    private M2MAccessTokenProvider m2mAccessTokenProvider;

    @BeforeAll
    static void seedBroker() {
        ScorpioInflator.addEntitiesToBroker(ContainerManager.getBaseUriForScorpioA(), BROKER_ENTITIES_JSON);
    }

    @AfterAll
    static void cleanUp() {
        ScorpioInflator.deleteInitialEntitiesFromContextBroker(ContainerManager.getBaseUriForScorpioA(), brokerEntityIds);
    }

    @Test
    void nodeAuthenticatesAgainstItsOwnJwtProtectedEndpointUsingARealM2MToken() {
        String accessToken = m2mAccessTokenProvider.getM2MAccessToken().block(Duration.ofSeconds(10));
        assertThat(accessToken).as("M2M token provider returned no access token").isNotBlank();

        String entitiesEndpoint = ContainerManager.getNodeALocalBaseUrl()
                + "/api/v2" + EndpointsConstants.GET_ENTITY + "/" + BrokerDataMother.GET_ENTITY_REQUEST_ENTITY_ID;

        var response = WebClient.create()
                .get()
                .uri(entitiesEndpoint)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .toBodilessEntity();

        StepVerifier.create(response)
                .consumeNextWith(entity -> assertThat(entity.getStatusCode().value()).isEqualTo(200))
                .verifyComplete();
    }
}
