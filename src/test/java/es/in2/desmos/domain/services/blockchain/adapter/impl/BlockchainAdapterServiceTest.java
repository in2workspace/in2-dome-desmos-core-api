package es.in2.desmos.domain.services.blockchain.adapter.impl;

import es.in2.desmos.domain.models.BlockchainSubscription;
import es.in2.desmos.domain.services.blockchain.adapter.BlockchainAdapterService;
import es.in2.desmos.infrastructure.configs.EndpointsConfig;
import es.in2.desmos.it.ContainerManager;
import es.in2.desmos.objectmothers.BlockchainSubscriptionMother;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

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

        var resultFlux = blockchainAdapterService.getSubscriptions("0");

        StepVerifier
                .create(resultFlux)
                .assertNext(result -> assertThat(result).isEqualTo(expected1))
                .assertNext(result -> assertThat(result).isEqualTo(expected2))
                .assertNext(result -> assertThat(result).isEqualTo(expected3))
                .verifyComplete();
    }

    private void createSubscriptions(BlockchainSubscription... blockchainSubscriptions) {
        for (var subscription : blockchainSubscriptions) {
            blockchainAdapterService.createSubscription("0", subscription).block();
        }
    }
}