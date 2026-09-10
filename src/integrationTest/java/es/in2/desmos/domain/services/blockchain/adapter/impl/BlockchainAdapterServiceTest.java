package es.in2.desmos.domain.services.blockchain.adapter.impl;

import es.in2.desmos.domain.models.BlockchainSubscription;
import es.in2.desmos.domain.services.blockchain.adapter.BlockchainAdapterService;
import es.in2.desmos.infrastructure.configs.EndpointsConfig;
import es.in2.desmos.support.ContainerManager;
import es.in2.desmos.objectmothers.BlockchainSubscriptionMother;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
@TestPropertySource(properties = "api.version=v2")
class BlockchainAdapterServiceTest {

    @Autowired
    private BlockchainAdapterService blockchainAdapterService;

    @Autowired
    private EndpointsConfig endpointsConfig;


    @DynamicPropertySource
    static void setDynamicProperties(DynamicPropertyRegistry registry) {
        ContainerManager.postgresqlProperties(registry);
    }

    @Test
    void itShouldGetSubscriptions() {
        BlockchainSubscription expected1 = BlockchainSubscriptionMother.defaultConfigured(endpointsConfig.dltNotificationEndpoint());
        BlockchainSubscription expected2 = BlockchainSubscriptionMother.sample(endpointsConfig.dltNotificationEndpoint());
        BlockchainSubscription expected3 = BlockchainSubscriptionMother.otherEventTypesSubscription(endpointsConfig.dltNotificationEndpoint());

        createSubscriptions(expected2, expected3);

        // expected1 is this context's own default subscription: ApplicationRunner creates it
        // against the DLT adapter at startup using the un-overridden application.yml
        // blockchain.notificationEndpoint ("http://desmos:8080/..."). Spring auto-subscribes
        // ApplicationRunner.onApplicationReady()'s returned Mono without blocking application
        // startup on it, so this can still be in flight when the test method runs -- poll rather
        // than assume it's already there. The DLT adapter container is also a JVM-wide singleton
        // shared with every other Spring context in this test JVM (see ContainerManager), so
        // assert presence of exactly these three rather than an exhaustive, order-dependent list.
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    var actual = blockchainAdapterService.getSubscriptions("0").collectList().block(Duration.ofSeconds(10));
                    assertThat(actual).contains(expected1, expected2, expected3);
                });
    }

    private void createSubscriptions(BlockchainSubscription... blockchainSubscriptions) {
        for (var subscription : blockchainSubscriptions) {
            blockchainAdapterService.createSubscription("0", subscription).block();
        }
    }
}