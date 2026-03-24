package es.in2.desmos.application.workflows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.application.workflows.impl.SubscribeWorkflowImpl;
import es.in2.desmos.domain.exceptions.JsonReadingException;
import es.in2.desmos.domain.models.AuditRecordStatus;
import es.in2.desmos.domain.models.BlockchainNotification;
import es.in2.desmos.domain.models.EventQueue;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.domain.services.api.QueueService;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import es.in2.desmos.domain.services.sync.services.DataSyncService;
import es.in2.desmos.objectmothers.BlockchainNotificationMother;
import es.in2.desmos.objectmothers.BrokerDataMother;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.NoSuchAlgorithmException;
import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscribeWorkflowTests {

    @Mock
    private QueueService pendingSubscribeEventsQueue;

    @Mock
    private BrokerPublisherService brokerPublisherService;

    @Mock
    private AuditRecordService auditRecordService;

    @Mock
    private DataSyncService dataSyncService;

    @Mock
    EventQueue eventQueue;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private SubscribeWorkflowImpl subscribeWorkflow;

    @ParameterizedTest
    @ValueSource(strings = {
            BrokerDataMother.GET_REAL_PRODUCT_OFFERING,
            BrokerDataMother.GET_REAL_CATEGORY,
            BrokerDataMother.GET_REAL_CATALOG
    })
    void itShouldVerifyAuditAndPublishedWhenIsRootObject(String retrievedBrokerEntity) throws NoSuchAlgorithmException, JsonProcessingException {
        String entityId = getEntityId(retrievedBrokerEntity);
        BlockchainNotification blockchainNotification = BlockchainNotificationMother.FromBrokerDataMother(retrievedBrokerEntity);
        EventQueue eventQueueMock = mock(EventQueue.class);

        when(eventQueueMock.getEvent()).thenReturn(List.of(blockchainNotification));
        when(pendingSubscribeEventsQueue.getEventStream()).thenReturn(Flux.just(eventQueueMock));
        when(pendingSubscribeEventsQueue.getEventStream())
                .thenReturn(Flux.just(eventQueueMock));
        when(eventQueueMock.getEvent().get(0))
                .thenReturn(List.of(blockchainNotification));
        when(dataSyncService.getEntityFromExternalSource(anyString(), eq(blockchainNotification)))
                .thenReturn(Flux.just(retrievedBrokerEntity));
        when(dataSyncService.verifyRetrievedEntityData(anyString(), eq(blockchainNotification), eq(retrievedBrokerEntity)))
                .thenReturn(Mono.empty());
        when(auditRecordService.buildAndSaveAuditRecordFromBlockchainNotification(anyString(), eq(blockchainNotification), eq(retrievedBrokerEntity), eq(AuditRecordStatus.RETRIEVED)))
                .thenReturn(Mono.empty());
        when(brokerPublisherService.publishDataToBroker(anyString(), eq(entityId), eq(retrievedBrokerEntity)))
                .thenReturn(Mono.empty());
        when(auditRecordService.buildAndSaveAuditRecordFromBlockchainNotification(anyString(), eq(blockchainNotification), eq(retrievedBrokerEntity), eq(AuditRecordStatus.PUBLISHED)))
                .thenReturn(Mono.empty());

        StepVerifier.create(subscribeWorkflow.startSubscribeWorkflow())
                .expectNextCount(0)
                .verifyComplete();

        verify(dataSyncService, times(1))
                .getEntityFromExternalSource(anyString(), eq(blockchainNotification));
        verify(dataSyncService, times(1))
                .verifyRetrievedEntityData(anyString(), eq(blockchainNotification), eq(retrievedBrokerEntity));
        verify(brokerPublisherService, times(1))
                .publishDataToBroker(anyString(), eq(entityId), eq(retrievedBrokerEntity));
        verify(auditRecordService, times(1))
                .buildAndSaveAuditRecordFromBlockchainNotification(
                        anyString(),
                        eq(blockchainNotification),
                        eq(retrievedBrokerEntity),
                        eq(AuditRecordStatus.RETRIEVED));
        verify(auditRecordService, times(1))
                .buildAndSaveAuditRecordFromBlockchainNotification(
                        anyString(),
                        eq(blockchainNotification),
                        eq(retrievedBrokerEntity),
                        eq(AuditRecordStatus.PUBLISHED));
    }

    @Test
    void itShouldAuditAndPublishedWhenHasRootTypeButIsSubEntity() throws JsonProcessingException {
        String retrievedBrokerEntity = BrokerDataMother.GET_REAL_PRODUCT_OFFERING;
        String entityId = getEntityId(retrievedBrokerEntity);
        String entityType = getEntityType(retrievedBrokerEntity);
        BlockchainNotification blockchainNotification = BlockchainNotificationMother.Empty();
        EventQueue eventQueueMock = mock(EventQueue.class);

        when(eventQueueMock.getEvent()).thenReturn(List.of(blockchainNotification));
        when(pendingSubscribeEventsQueue.getEventStream()).thenReturn(Flux.just(eventQueueMock));
        when(pendingSubscribeEventsQueue.getEventStream())
                .thenReturn(Flux.just(eventQueueMock));
        when(eventQueueMock.getEvent().get(0))
                .thenReturn(List.of(blockchainNotification));
        when(dataSyncService.getEntityFromExternalSource(anyString(), eq(blockchainNotification)))
                .thenReturn(Flux.just(retrievedBrokerEntity));
        when(auditRecordService.buildAndSaveAuditRecordForSubEntity(anyString(), eq(entityId), eq(entityType), eq(retrievedBrokerEntity), eq(AuditRecordStatus.RETRIEVED)))
                .thenReturn(Mono.empty());
        when(brokerPublisherService.publishDataToBroker(anyString(), eq(entityId), eq(retrievedBrokerEntity)))
                .thenReturn(Mono.empty());
        when(auditRecordService.buildAndSaveAuditRecordForSubEntity(anyString(), eq(entityId), eq(entityType), eq(retrievedBrokerEntity), eq(AuditRecordStatus.PUBLISHED)))
                .thenReturn(Mono.empty());

        StepVerifier.create(subscribeWorkflow.startSubscribeWorkflow())
                .expectNextCount(0)
                .verifyComplete();

        verify(dataSyncService, times(1))
                .getEntityFromExternalSource(anyString(), eq(blockchainNotification));
        verify(dataSyncService, times(0))
                .verifyRetrievedEntityData(any(), any(), any());
        verify(brokerPublisherService, times(1))
                .publishDataToBroker(anyString(), eq(entityId), eq(retrievedBrokerEntity));
        verify(auditRecordService, times(1))
                .buildAndSaveAuditRecordForSubEntity(
                        anyString(),
                        eq(entityId),
                        eq(entityType),
                        eq(retrievedBrokerEntity),
                        eq(AuditRecordStatus.RETRIEVED));
        verify(auditRecordService, times(1))
                .buildAndSaveAuditRecordForSubEntity(
                        anyString(),
                        eq(entityId),
                        eq(entityType),
                        eq(retrievedBrokerEntity),
                        eq(AuditRecordStatus.PUBLISHED));
    }

    @Test
    void itShouldPublishWhenIsSubEntity() throws JsonProcessingException {
        String retrievedBrokerEntity = BrokerDataMother.NOT_ROOT_OBJECT;
        String entityId = getEntityId(retrievedBrokerEntity);
        BlockchainNotification blockchainNotification = BlockchainNotificationMother.Empty();
        EventQueue eventQueueMock = mock(EventQueue.class);

        when(eventQueueMock.getEvent()).thenReturn(List.of(blockchainNotification));
        when(pendingSubscribeEventsQueue.getEventStream()).thenReturn(Flux.just(eventQueueMock));
        when(pendingSubscribeEventsQueue.getEventStream())
                .thenReturn(Flux.just(eventQueueMock));
        when(eventQueueMock.getEvent().get(0))
                .thenReturn(List.of(blockchainNotification));
        when(dataSyncService.getEntityFromExternalSource(anyString(), eq(blockchainNotification)))
                .thenReturn(Flux.just(retrievedBrokerEntity));
        when(brokerPublisherService.publishDataToBroker(anyString(), eq(entityId), eq(retrievedBrokerEntity)))
                .thenReturn(Mono.empty());

        StepVerifier.create(subscribeWorkflow.startSubscribeWorkflow())
                .expectNextCount(0)
                .verifyComplete();

        verify(dataSyncService, times(1))
                .getEntityFromExternalSource(anyString(), eq(blockchainNotification));
        verify(dataSyncService, times(0))
                .verifyRetrievedEntityData(any(), any(), any());
        verify(brokerPublisherService, times(1))
                .publishDataToBroker(anyString(), eq(entityId), eq(retrievedBrokerEntity));
        verifyNoInteractions(auditRecordService);
    }

    @Test
    void itShouldReturnMonoEmptyIfEntityFromExternalSourceRaiseExceptionWorkflow() {
        BlockchainNotification blockchainNotification = BlockchainNotificationMother.Empty();

        when(eventQueue.getEvent()).thenReturn(List.of(blockchainNotification));
        when(pendingSubscribeEventsQueue.getEventStream()).thenReturn(Flux.just(eventQueue));
        when(pendingSubscribeEventsQueue.getEventStream())
                .thenReturn(Flux.just(eventQueue));
        when(eventQueue.getEvent().get(0))
                .thenReturn(List.of(blockchainNotification));
        when(dataSyncService.getEntityFromExternalSource(any(), any())).thenReturn(Flux.error(new JsonReadingException("Error reading json")));

        StepVerifier.create(subscribeWorkflow.startSubscribeWorkflow())
                .expectNextCount(0)
                .verifyComplete();
    }

    @Test
    void itShouldRaiseExceptionIfEntityIdJsonIsIncorrect() throws JsonProcessingException {
        BlockchainNotification blockchainNotification = BlockchainNotificationMother.Empty();

        when(eventQueue.getEvent()).thenReturn(List.of(blockchainNotification));
        when(pendingSubscribeEventsQueue.getEventStream()).thenReturn(Flux.just(eventQueue));
        when(pendingSubscribeEventsQueue.getEventStream())
                .thenReturn(Flux.just(eventQueue));
        when(eventQueue.getEvent().get(0))
                .thenReturn(List.of(blockchainNotification));
        when(dataSyncService.getEntityFromExternalSource(anyString(), eq(blockchainNotification)))
                .thenReturn(Flux.just(BrokerDataMother.WITHOUT_ID));
        when(objectMapper.readTree(anyString())).thenThrow(new JsonProcessingException("Error") {
        });

        StepVerifier.create(subscribeWorkflow.startSubscribeWorkflow())
                .expectNextCount(0)
                .verifyComplete();
    }

    @Test
    void itShouldRaiseExceptionIfEntityTypeJsonIsIncorrect() throws JsonProcessingException {
        BlockchainNotification blockchainNotification = BlockchainNotificationMother.Empty();

        when(eventQueue.getEvent()).thenReturn(List.of(blockchainNotification));
        when(pendingSubscribeEventsQueue.getEventStream()).thenReturn(Flux.just(eventQueue));
        when(pendingSubscribeEventsQueue.getEventStream())
                .thenReturn(Flux.just(eventQueue));
        when(eventQueue.getEvent().get(0))
                .thenReturn(List.of(blockchainNotification));
        when(dataSyncService.getEntityFromExternalSource(anyString(), eq(blockchainNotification)))
                .thenReturn(Flux.just(BrokerDataMother.WITHOUT_ID));
        when(objectMapper.readTree(anyString()))
                .thenCallRealMethod()
                .thenThrow(new JsonProcessingException("Error") {
                });

        StepVerifier.create(subscribeWorkflow.startSubscribeWorkflow())
                .expectNextCount(0)
                .verifyComplete();
    }

    private String getEntityId(String brokerData) throws JsonProcessingException {
        JsonNode jsonEntity = objectMapper.readTree(brokerData);
        return jsonEntity.get("id").asText();
    }

    private String getEntityType(String brokerData) throws JsonProcessingException {
        JsonNode jsonEntity = objectMapper.readTree(brokerData);
        return jsonEntity.get("type").asText();
    }

}