package es.in2.desmos.domain.services.blockchain.impl;

import es.in2.desmos.domain.models.BlockchainSubscription;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.domain.services.api.QueueService;
import es.in2.desmos.domain.services.blockchain.adapter.BlockchainAdapterService;
import es.in2.desmos.domain.services.blockchain.adapter.factory.BlockchainAdapterFactory;
import es.in2.desmos.infrastructure.configs.EndpointsConfig;
import es.in2.desmos.infrastructure.configs.TrustFrameworkConfig;
import es.in2.desmos.objectmothers.BlockchainSubscriptionMother;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.stream.Stream;

import static es.in2.desmos.domain.utils.EndpointsConstants.DLT_ADAPTER_NOTIFICATION;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlockchainListenerServiceTest {

    @Mock
    private BlockchainAdapterFactory blockchainAdapterFactory;

    @Mock
    private BlockchainAdapterService blockchainAdapterService;

    @Mock
    private AuditRecordService auditRecordService;

    @Mock
    private QueueService pendingSubscribeEventsQueue;

    @InjectMocks
    private BlockchainListenerServiceImpl blockchainListenerService;

    @Mock
    private TrustFrameworkConfig trustFrameworkConfig;

    @Mock
    private EndpointsConfig endpointsConfig;

    private static String dltendpoint = "/api/v2"+DLT_ADAPTER_NOTIFICATION;

    @BeforeEach
    void init() {
        when(blockchainAdapterFactory.getBlockchainAdapter()).thenReturn(blockchainAdapterService);
        when(endpointsConfig.dltNotificationEndpoint()).thenReturn(dltendpoint);
        blockchainListenerService = new BlockchainListenerServiceImpl(blockchainAdapterFactory, auditRecordService, pendingSubscribeEventsQueue, trustFrameworkConfig);
    }

    @Test
    void itShouldCreateSubscriptionIfNotExistsAny() {
        String processId = "0";

        when(blockchainAdapterService.getSubscriptions(processId))
                .thenReturn(Flux.empty());

        var blockchainSubscription = BlockchainSubscriptionMother.sample(endpointsConfig.dltNotificationEndpoint());
        when(blockchainAdapterService.createSubscription(processId, blockchainSubscription))
                .thenReturn(Mono.empty());

        var result = blockchainListenerService.createSubscription(processId, blockchainSubscription);

        StepVerifier
                .create(result)
                .verifyComplete();

        verify(blockchainAdapterService, times(1)).getSubscriptions(processId);
        verify(blockchainAdapterService, times(1)).createSubscription(processId, blockchainSubscription);
    }

    @ParameterizedTest
    @MethodSource("getDifferentSubscriptions")
    void itShouldCreateSubscriptionIfItsDifferentThanExisting(BlockchainSubscription differentSubscription) {
        String processId = "0";

        when(blockchainAdapterService.getSubscriptions(processId))
                .thenReturn(Flux.just(differentSubscription));

        var blockchainSubscription = BlockchainSubscriptionMother.sample(endpointsConfig.dltNotificationEndpoint());
        when(blockchainAdapterService.createSubscription(processId, blockchainSubscription))
                .thenReturn(Mono.empty());

        var result = blockchainListenerService.createSubscription(processId, blockchainSubscription);

        StepVerifier
                .create(result)
                .verifyComplete();

        verify(blockchainAdapterService, times(1)).getSubscriptions(processId);
        verify(blockchainAdapterService, times(1)).createSubscription(processId, blockchainSubscription);
    }

    @Test
    void itShouldNotCreateSubscriptionIfItsEqualThanExisting() {
        String processId = "0";
        var blockchainSubscription = BlockchainSubscriptionMother.sample(endpointsConfig.dltNotificationEndpoint());

        when(blockchainAdapterService.getSubscriptions(processId))
                .thenReturn(Flux.just(blockchainSubscription));

        var result = blockchainListenerService.createSubscription(processId, blockchainSubscription);

        StepVerifier
                .create(result)
                .verifyComplete();

        verify(blockchainAdapterService, times(1)).getSubscriptions(processId);
        verify(blockchainAdapterService, times(0)).createSubscription(processId, blockchainSubscription);
    }

    private static Stream<BlockchainSubscription> getDifferentSubscriptions() {
        return Stream.of(
                BlockchainSubscriptionMother.otherEventTypesSubscription(dltendpoint),
                BlockchainSubscriptionMother.otherNotificationEndpointSubscription(dltendpoint));
    }

}