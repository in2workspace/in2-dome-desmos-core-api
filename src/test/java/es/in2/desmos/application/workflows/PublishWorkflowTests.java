package es.in2.desmos.application.workflows;

import es.in2.desmos.application.workflows.impl.PublishWorkflowImpl;
import es.in2.desmos.domain.models.AuditRecordStatus;
import es.in2.desmos.domain.models.BlockchainTxPayload;
import es.in2.desmos.domain.models.BrokerNotification;
import es.in2.desmos.domain.models.EventQueue;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.domain.services.api.QueueService;
import es.in2.desmos.domain.services.blockchain.BlockchainPublisherService;
import es.in2.desmos.domain.utils.BlockchainTxPayloadFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublishWorkflowTests {
    
    private final String id = "1234";
    private final String type = "TestType";
    private final List<Map<String, Object>> data = List.of(Collections.singletonMap("id", "123"));
    private final String subscriptionId = "urn:ngsi-ld:Subscription:7230a0ac-ed33-44e4-bb04-ce0f345b9d2a";
    private final String notifiedAt = "2024-01-01T12:00:00Z";

    BrokerNotification brokerNotification = BrokerNotification.builder()
            .id(id)
            .type(type)
            .data(data)
            .subscriptionId(subscriptionId)
            .notifiedAt(notifiedAt)
            .build();

    @Mock
    private QueueService pendingPublishEventsQueue;
    @Mock
    private AuditRecordService auditRecordService;
    @Mock
    private BlockchainTxPayloadFactory blockchainTxPayloadFactory;
    @Mock
    private BlockchainPublisherService blockchainPublisherService;
    @InjectMocks
    private PublishWorkflowImpl publishWorkflow;


    @Test
    void testBuildBlockchainTxPayload() {
        Map<String, Object> blockchainData = new HashMap<>();
        blockchainData.put("id", "123");
        String previousHash = "5d41402abc4b2a76b9719d911017c592";
        EventQueue eventQueueMock = mock(EventQueue.class);

        BlockchainTxPayload blockchainTxPayload = BlockchainTxPayload.builder().build();

        when(eventQueueMock.getEvent()).thenReturn(List.of(brokerNotification));
        when(pendingPublishEventsQueue.getEventStream()).thenReturn(Flux.just(eventQueueMock));
        when(eventQueueMock.getEvent().get(0)).thenReturn(List.of(brokerNotification));
        when(auditRecordService.fetchLatestProducerEntityHashLinkByEntityId(anyString(), eq("123"))).thenReturn(Mono.empty());
        when(blockchainTxPayloadFactory.calculatePreviousHashIfEmpty(anyString(), any())).thenReturn(Mono.just(previousHash));
        when(blockchainTxPayloadFactory.buildBlockchainTxPayload(anyString(), anyMap(), anyString()))
                .thenReturn(Mono.just(blockchainTxPayload));
        when(auditRecordService.buildAndSaveAuditRecordFromBrokerNotification(anyString(), any(), eq(AuditRecordStatus.CREATED), any()))
                .thenReturn(Mono.empty());
        when(blockchainPublisherService.publishDataToBlockchain(anyString(), eq(blockchainTxPayload)))
                .thenReturn(Mono.empty());
        when(auditRecordService.buildAndSaveAuditRecordFromBrokerNotification(anyString(), any(), eq(AuditRecordStatus.PUBLISHED), any()))
                .thenReturn(Mono.empty());

        publishWorkflow.startPublishWorkflow().blockLast();

        verify(blockchainTxPayloadFactory).buildBlockchainTxPayload(anyString(), eq(blockchainData), eq(previousHash));
        verify(auditRecordService).buildAndSaveAuditRecordFromBrokerNotification(anyString(), any(), eq(AuditRecordStatus.CREATED), any());
        verify(blockchainPublisherService).publishDataToBlockchain(anyString(), any());
        verify(auditRecordService).buildAndSaveAuditRecordFromBrokerNotification(anyString(), any(), eq(AuditRecordStatus.PUBLISHED), any());
    }

}
