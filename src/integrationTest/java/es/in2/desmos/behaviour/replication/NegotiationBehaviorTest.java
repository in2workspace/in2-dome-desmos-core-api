package es.in2.desmos.behaviour.replication;

import es.in2.desmos.domain.repositories.AuditRecordRepository;
import es.in2.desmos.inflators.ScorpioInflator;
import es.in2.desmos.objectmothers.EntityMother;
import es.in2.desmos.support.ContainerManager;
import es.in2.desmos.support.ScorpioProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * R3/R4 -- P2P negotiation's UPDATE and SKIP outcomes, the decision paths
 * {@link P2PDataSyncBehaviorTest}'s ADD case doesn't exercise. Same trigger
 * ({@code /backoffice/v2/actions/sync}, driving node A to discover from node B) applied to a pair
 * of same-id fixtures seeded differently on each broker -- no upsert helper needed, since seeding
 * a different variant per broker is enough, and {@link P2PDataSyncBehaviorTest} already proves
 * discovery itself works without any pre-existing audit record on the seeded side.
 * <p>
 * Per {@code DataNegotiationJobImpl}: a same-id external entity with a strictly higher version
 * lands in the "entities to add" list (an overwrite, despite the name); a same-id, same-version
 * external entity with a strictly newer {@code lastUpdate} lands in "entities to update"; anything
 * else is skipped outright, with zero CONSUMER audit rows written for it.
 */
class NegotiationBehaviorTest extends AbstractReplicationBehaviorTest {

    private static final String PRODUCT_OFFERING_2_ID = "urn:product-offering:ed9c56c8-a5ab-42cc-bc62-0fca69a30c87";
    private static final String PRODUCT_OFFERING_3_ID = "urn:product-offering:537e1ee3-0556-4fff-875f-e55bb97e7ab0";

    @Autowired
    private AuditRecordRepository auditRecordRepository;

    @AfterEach
    void cleanUp() {
        List<String> ids = List.of(PRODUCT_OFFERING_2_ID, PRODUCT_OFFERING_3_ID);
        ScorpioInflator.deleteInitialEntitiesFromContextBroker(ContainerManager.getBaseUriForScorpioA(), ids);
        ScorpioInflator.deleteInitialEntitiesFromContextBroker(ContainerManager.getBaseUriForScorpioB(), ids);
        auditRecordRepository.deleteAll().block();
    }

    @Test
    void updatesLocalCopyWhenExternalVersionIsHigher() {
        seed(ContainerManager.getBaseUriForScorpioA(), EntityMother.PRODUCT_OFFERING_2_OLD);
        seed(ContainerManager.getBaseUriForScorpioB(), EntityMother.PRODUCT_OFFERING_2);

        triggerNodeASync();

        awaitNodeAEntityMatches(PRODUCT_OFFERING_2_ID, EntityMother.PRODUCT_OFFERING_2);
    }

    @Test
    void updatesLocalCopyWhenSameVersionButExternalLastUpdateIsNewer() {
        seed(ContainerManager.getBaseUriForScorpioA(), EntityMother.PRODUCT_OFFERING_3_OLD);
        seed(ContainerManager.getBaseUriForScorpioB(), EntityMother.PRODUCT_OFFERING_3);

        triggerNodeASync();

        awaitNodeAEntityMatches(PRODUCT_OFFERING_3_ID, EntityMother.PRODUCT_OFFERING_3);
    }

    @Test
    void skipsWhenLocalCopyIsAlreadyNewer() {
        seed(ContainerManager.getBaseUriForScorpioA(), EntityMother.PRODUCT_OFFERING_2);
        seed(ContainerManager.getBaseUriForScorpioB(), EntityMother.PRODUCT_OFFERING_2_OLD);

        triggerNodeASync();

        // A negative outcome has no distinct "done" signal to poll for, so hold a stable window
        // instead of asserting once immediately after the sync call returns.
        await().pollInterval(Duration.ofSeconds(2))
                .during(Duration.ofSeconds(20))
                .atMost(Duration.ofSeconds(25))
                .untilAsserted(() -> {
                    String actual = ScorpioProbe.getEntity(ContainerManager.getBaseUriForScorpioA(), PRODUCT_OFFERING_2_ID);
                    JSONAssert.assertEquals(EntityMother.PRODUCT_OFFERING_2, actual, false);

                    boolean hasConsumerRow = auditRecordRepository.findByEntityId(PRODUCT_OFFERING_2_ID)
                            .collectList().block(Duration.ofSeconds(5))
                            .stream().anyMatch(record -> record.getTrader().name().equalsIgnoreCase("CONSUMER"));
                    assertThat(hasConsumerRow)
                            .as("a locally-newer entity should never be synced in, so no CONSUMER audit row should exist for it")
                            .isFalse();
                });
    }

    private void seed(String brokerBaseUri, String entityJson) {
        ScorpioInflator.addEntitiesToBroker(brokerBaseUri, "[" + entityJson + "]");
    }

    private void triggerNodeASync() {
        WebClient.create()
                .get()
                .uri(ContainerManager.getNodeALocalBaseUrl() + "/backoffice/v2/actions/sync")
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(30));
    }

    private void awaitNodeAEntityMatches(String entityId, String expectedJson) {
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    String actual = ScorpioProbe.getEntity(ContainerManager.getBaseUriForScorpioA(), entityId);
                    assertThat(actual).as("entity not yet updated on node A's broker").isNotNull();
                    JSONAssert.assertEquals(expectedJson, actual, false);
                });
    }
}
