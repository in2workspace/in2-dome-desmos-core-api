package es.in2.desmos.domain.services.blockchain.impl;

import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;
import java.util.Set;
import java.util.List;
import es.in2.desmos.testutils.LogCapture;
import es.in2.desmos.domain.models.BlockchainNotification;
import es.in2.desmos.domain.exceptions.UnauthorizedDomeParticipantException;
import ch.qos.logback.classic.Level;
import es.in2.desmos.domain.exceptions.SubscriptionLookupException;
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

import java.time.Duration;
import java.util.concurrent.TimeoutException;
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

    private final static String dltEndpoint = "/api/v2"+DLT_ADAPTER_NOTIFICATION;

    @BeforeEach
    void init() {
        when(blockchainAdapterFactory.getBlockchainAdapter()).thenReturn(blockchainAdapterService);
        lenient().when(endpointsConfig.dltNotificationEndpoint()).thenReturn(dltEndpoint);
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

    @Test
    void itShouldFailAsALookupWhenListingTheSubscriptionsHangs() {
        String processId = "0";
        var blockchainSubscription = BlockchainSubscriptionMother.sample(endpointsConfig.dltNotificationEndpoint());

        when(blockchainAdapterService.getSubscriptions(processId)).thenReturn(Flux.never());

        StepVerifier
                .withVirtualTime(() -> blockchainListenerService.createSubscription(processId, blockchainSubscription))
                .thenAwait(SubscriptionLookupException.TIMEOUT)
                .expectErrorMatches(error -> error instanceof SubscriptionLookupException
                        && error.getCause() instanceof TimeoutException)
                .verify(Duration.ofSeconds(30));

        verify(blockchainAdapterService, times(0)).createSubscription(processId, blockchainSubscription);
    }

    private static Stream<BlockchainSubscription> getDifferentSubscriptions() {
        return Stream.of(
                BlockchainSubscriptionMother.otherEventTypesSubscription(dltEndpoint),
                BlockchainSubscriptionMother.otherNotificationEndpointSubscription(dltEndpoint));
    }

    @Test
    void itShouldLogAnEventFromATrustedPublisherWhenItIsEnqueued() {
        BlockchainNotification blockchainNotification = BlockchainNotification.builder()
                .id(1039312)
                .publisherAddress("0x3d4bb0c562a44d4e579b8e0f98934f104c8bd1410e7c54660d70c147eaf9faa6")
                .eventType("product-offering")
                .timestamp(1762104240L)
                .dataLocation("http://desmos-node-b:8080/api/v2/entities/urn:product-offering:b-2?hl=abcd")
                .relevantMetadata(List.of())
                .entityId("0x1234")
                .previousEntityHashLink("0x0000")
                .ethereumAddress("0x4bae4559eC81efc94210134Cc5b2Ef692387b233")
                .build();
        when(trustFrameworkConfig.getDltAddresses()).thenReturn(Set.of("0x4bae4559ec81efc94210134cc5b2ef692387b233"));
        when(auditRecordService.buildAndSaveAuditRecordFromBlockchainNotification(any(), any(), any(), any())).thenReturn(Mono.empty());
        when(pendingSubscribeEventsQueue.enqueueEvent(any())).thenReturn(Mono.empty());

        try (LogCapture logCapture = LogCapture.of(BlockchainListenerServiceImpl.class)) {
            StepVerifier.create(blockchainListenerService.processBlockchainNotification("0", blockchainNotification))
                    .verifyComplete();

            assertThat(logCapture.messages(Level.INFO)).containsExactly("[subscribe] ProcessID: 0 - product-offering "
                    + "urn:product-offering:b-2 event=1039312 from 0x4bae4559eC81efc94210134Cc5b2Ef692387b233 "
                    + "(DLT Address valid) -> enqueued");
        }
    }

    @Test
    void itShouldLogAnEventFromAnUntrustedPublisherAsAnUnexpectedError() {
        BlockchainNotification blockchainNotification = BlockchainNotification.builder()
                .id(1039319)
                .publisherAddress("0xc23e2a4bbc11c826c53fc84c6616101a14077813c29d7c131dea77d90518d009")
                .eventType("individual")
                .timestamp(1762104240L)
                .dataLocation("https://desmos.dome-marketplace-lcl.org/api/v2/entities/urn:ngsi-ld:individual:1?hl=abcd")
                .relevantMetadata(List.of())
                .entityId("0x1234")
                .previousEntityHashLink("0x0000")
                .ethereumAddress("0x8247479b84b291bce9f2dd598579a3dfFc3C2d97")
                .build();
        when(trustFrameworkConfig.getDltAddresses()).thenReturn(Set.of("0x4bae4559ec81efc94210134cc5b2ef692387b233"));
        // Built eagerly by the chain; never subscribed when the publisher is rejected
        AtomicBoolean auditSaved = new AtomicBoolean();
        when(auditRecordService.buildAndSaveAuditRecordFromBlockchainNotification(any(), any(), any(), any()))
                .thenReturn(Mono.fromRunnable(() -> auditSaved.set(true)));

        try (LogCapture logCapture = LogCapture.of(BlockchainListenerServiceImpl.class)) {
            StepVerifier.create(blockchainListenerService.processBlockchainNotification("0", blockchainNotification))
                    .expectError(UnauthorizedDomeParticipantException.class)
                    .verify();

            assertThat(logCapture.messages(Level.ERROR)).containsExactly("[subscribe] ProcessID: 0 - Dome Participant not found: "
                    + "DLT Address 0x8247479b84b291bce9f2dd598579a3dfFc3C2d97 from Blockchain notification is not in the trusted "
                    + "access nodes list, notification ignored - individual urn:ngsi-ld:individual:1 (event id 1039319, "
                    + "iss 0xc23e2a4bbc11c826c53fc84c6616101a14077813c29d7c131dea77d90518d009, "
                    + "dataLocation https://desmos.dome-marketplace-lcl.org/api/v2/entities/urn:ngsi-ld:individual:1?hl=abcd)");
        }
        assertThat(auditSaved).isFalse();
        verifyNoInteractions(pendingSubscribeEventsQueue);
    }
}
