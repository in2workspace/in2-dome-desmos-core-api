package es.in2.desmos.behaviour.replication;

import es.in2.desmos.inflators.ScorpioInflator;
import es.in2.desmos.support.ContainerManager;
import es.in2.desmos.support.ScorpioProbe;
import es.in2.desmos.objectmothers.EntityMother;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Two-node P2P replication: node B holds an entity node A has never seen. Triggering node
 * A's P2P sync (the public {@code /backoffice/v2/actions/sync} endpoint) should make A
 * discover it on B (an authenticated {@code POST /api/v2/sync/p2p/discovery} call, using a
 * real M2M token minted against the shared {@link es.in2.desmos.support.VerifierStub}),
 * negotiate it as an ADD (node A has nothing with this id), fetch it (an authenticated
 * {@code POST /api/v2/sync/p2p/entities} call), verify its hash, and upsert it into A's own
 * Scorpio broker.
 */
class P2PDataSyncBehaviorTest extends AbstractReplicationBehaviorTest {

    // EntityMother.CATEGORY's own id -- a flat "category" entity with no sub-entity
    // relationships, valid lifecycleStatus/validFor/lastUpdate so it clears replication
    // policy filtering and version/lastUpdate negotiation.
    private static final String CATEGORY_ENTITY_ID = "urn:category:1";

    @BeforeAll
    static void seedNodeBOnly() {
        ScorpioInflator.addEntitiesToBroker(
                ContainerManager.getBaseUriForScorpioB(),
                "[" + EntityMother.CATEGORY + "]");
    }

    @AfterAll
    static void cleanUp() {
        ScorpioInflator.deleteInitialEntitiesFromContextBroker(
                ContainerManager.getBaseUriForScorpioB(), List.of(CATEGORY_ENTITY_ID));
        ScorpioInflator.deleteInitialEntitiesFromContextBroker(
                ContainerManager.getBaseUriForScorpioA(), List.of(CATEGORY_ENTITY_ID));
    }

    @Test
    void nodeAPullsAnEntityThatOnlyExistsOnNodeBViaP2PSync() {
        WebClient.create()
                .get()
                .uri(ContainerManager.getNodeALocalBaseUrl() + "/backoffice/v2/actions/sync")
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(30));

        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    String actual = ScorpioProbe.getEntityOrNull(ContainerManager.getBaseUriForScorpioA(), CATEGORY_ENTITY_ID);
                    assertThat(actual).as("category entity not yet replicated onto node A's broker").isNotNull();
                    JSONAssert.assertEquals(EntityMother.CATEGORY, actual, false);
                });
    }
}
