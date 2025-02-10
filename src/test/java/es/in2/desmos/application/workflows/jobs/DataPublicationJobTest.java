package es.in2.desmos.application.workflows.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import es.in2.desmos.application.workflows.jobs.impl.DataPublicationJobImpl;
import es.in2.desmos.domain.models.*;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import es.in2.desmos.objectmothers.DataNegotiationResultMother;
import es.in2.desmos.objectmothers.EntityMother;
import es.in2.desmos.objectmothers.MVAuditServiceEntity4DataNegotiationMother;
import es.in2.desmos.objectmothers.MVEntity4DataNegotiationMother;
import org.json.JSONException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataPublicationJobTest {

    @InjectMocks
    private DataPublicationJobImpl dataVerificationJob;
    @Mock
    private AuditRecordService auditRecordService;
    @Mock
    private BrokerPublisherService brokerPublisherService;

    @Captor
    private ArgumentCaptor<MVAuditServiceEntity4DataNegotiation> mvAuditServiceEntity4DataNegotiationArgumentCaptor;


    @Test
    void itShouldBuildAnSaveAuditRecord() throws JsonProcessingException, JSONException, NoSuchAlgorithmException {
        DataNegotiationResult dataNegotiationResult = DataNegotiationResultMother.newToSync4AndExistingToSync2();

        String processId = "0";

        when(auditRecordService.buildAndSaveAuditRecordFromDataSync(any(), any(), any(), any())).thenReturn(Mono.empty());

        when(brokerPublisherService.publishDataToBroker(any(), any(), any())).thenReturn(Mono.empty());

        Mono<String> issuer = Mono.just("http://example.org");

        Map<Id, Entity> entitiesById = new HashMap<>();
        entitiesById.put(new Id(MVEntity4DataNegotiationMother.sample2().id()), new Entity(EntityMother.PRODUCT_OFFERING_2));
        entitiesById.put(new Id(MVEntity4DataNegotiationMother.sample4().id()), new Entity(EntityMother.PRODUCT_OFFERING_4));

        List<MVEntity4DataNegotiation> allMVEntity4DataNegotiation = new ArrayList<>();
        allMVEntity4DataNegotiation.add(MVEntity4DataNegotiationMother.sample2());
        allMVEntity4DataNegotiation.add(MVEntity4DataNegotiationMother.sample4());

        Map<Id, HashAndHashLink> existingEntitiesOriginalValidationDataById = new HashMap<>();
        existingEntitiesOriginalValidationDataById.put(new Id(MVEntity4DataNegotiationMother.sample2().id()), new HashAndHashLink(MVEntity4DataNegotiationMother.sample2().hash(), MVEntity4DataNegotiationMother.sample2().hashlink()));

        Mono<Void> result = dataVerificationJob.verifyData(processId, issuer, Mono.just(entitiesById), Mono.just(allMVEntity4DataNegotiation), Mono.just(existingEntitiesOriginalValidationDataById));

        StepVerifier.
                create(result)
                .verifyComplete();

        verify(auditRecordService, times(2)).buildAndSaveAuditRecordFromDataSync(eq(processId), eq(dataNegotiationResult.issuer()), any(), eq(AuditRecordStatus.RETRIEVED));
        verify(auditRecordService, times(2)).buildAndSaveAuditRecordFromDataSync(eq(processId), eq(dataNegotiationResult.issuer()), any(), eq(AuditRecordStatus.PUBLISHED));
    }

    @Test
    void itShouldNotBuildAnSaveAuditRecordForSubEntity() throws JsonProcessingException, JSONException, NoSuchAlgorithmException {

        String processId = "0";

        when(auditRecordService.buildAndSaveAuditRecordFromDataSync(any(), any(), any(), any())).thenReturn(Mono.empty());

        when(brokerPublisherService.publishDataToBroker(any(), any(), any())).thenReturn(Mono.empty());

        Mono<String> issuer = Mono.just("http://example.org");

        Map<Id, Entity> entitiesById = new HashMap<>();
        entitiesById.put(new Id(MVEntity4DataNegotiationMother.sample2().id()), new Entity(EntityMother.PRODUCT_OFFERING_2));
        entitiesById.put(new Id(MVEntity4DataNegotiationMother.sample4().id()), new Entity(EntityMother.PRODUCT_OFFERING_4));

        List<MVEntity4DataNegotiation> allMVEntity4DataNegotiation = new ArrayList<>();
        allMVEntity4DataNegotiation.add(MVEntity4DataNegotiationMother.sample4());

        Map<Id, HashAndHashLink> existingEntitiesOriginalValidationDataById = new HashMap<>();
        existingEntitiesOriginalValidationDataById.put(new Id(MVEntity4DataNegotiationMother.sample2().id()), new HashAndHashLink(MVEntity4DataNegotiationMother.sample2().hash(), MVEntity4DataNegotiationMother.sample2().hashlink()));

        Mono<Void> result = dataVerificationJob.verifyData(processId, issuer, Mono.just(entitiesById), Mono.just(allMVEntity4DataNegotiation), Mono.just(existingEntitiesOriginalValidationDataById));

        StepVerifier.
                create(result)
                .verifyComplete();

        verify(auditRecordService, times(1)).buildAndSaveAuditRecordFromDataSync(eq(processId), eq("http://example.org"), mvAuditServiceEntity4DataNegotiationArgumentCaptor.capture(), eq(AuditRecordStatus.RETRIEVED));
        verify(auditRecordService, times(1)).buildAndSaveAuditRecordFromDataSync(eq(processId), eq("http://example.org"), any(), eq(AuditRecordStatus.PUBLISHED));

        var mvEntity4DataNegotiationSentToAuditRecord = mvAuditServiceEntity4DataNegotiationArgumentCaptor.getAllValues();

        assertThat(mvEntity4DataNegotiationSentToAuditRecord.get(0)).isEqualTo(MVAuditServiceEntity4DataNegotiationMother.sample4());
    }

    @Test
    void itShouldUpsertEntities() throws JsonProcessingException, JSONException, NoSuchAlgorithmException {
        String processId = "0";

        when(auditRecordService.buildAndSaveAuditRecordFromDataSync(any(), any(), any(), any())).thenReturn(Mono.empty());

        when(brokerPublisherService.publishDataToBroker(any(), any(), any())).thenReturn(Mono.empty());

        Mono<String> issuer = Mono.just("http://example.org");

        Map<Id, Entity> entitiesById = new HashMap<>();
        String productOffering2 = EntityMother.PRODUCT_OFFERING_2;
        entitiesById.put(new Id(MVEntity4DataNegotiationMother.sample2().id()), new Entity(productOffering2));
        String productOffering4 = EntityMother.PRODUCT_OFFERING_4;
        entitiesById.put(new Id(MVEntity4DataNegotiationMother.sample4().id()), new Entity(productOffering4));

        List<MVEntity4DataNegotiation> allMVEntity4DataNegotiation = new ArrayList<>();
        allMVEntity4DataNegotiation.add(MVEntity4DataNegotiationMother.sample2());
        allMVEntity4DataNegotiation.add(MVEntity4DataNegotiationMother.sample4());

        Map<Id, HashAndHashLink> existingEntitiesOriginalValidationDataById = new HashMap<>();
        existingEntitiesOriginalValidationDataById.put(new Id(MVEntity4DataNegotiationMother.sample2().id()), new HashAndHashLink(MVEntity4DataNegotiationMother.sample2().hash(), MVEntity4DataNegotiationMother.sample2().hashlink()));

        Mono<Void> result = dataVerificationJob.verifyData(processId, issuer, Mono.just(entitiesById), Mono.just(allMVEntity4DataNegotiation), Mono.just(existingEntitiesOriginalValidationDataById));


        StepVerifier.
                create(result)
                .verifyComplete();

        verify(brokerPublisherService, times(1)).publishDataToBroker(processId, MVEntity4DataNegotiationMother.sample2().id(), productOffering2);
        verify(brokerPublisherService, times(1)).publishDataToBroker(processId, MVEntity4DataNegotiationMother.sample4().id(), productOffering4);
        verifyNoMoreInteractions(brokerPublisherService);
    }
}