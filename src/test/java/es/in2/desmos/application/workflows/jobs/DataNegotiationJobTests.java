package es.in2.desmos.application.workflows.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import es.in2.desmos.application.workflows.jobs.impl.DataNegotiationJobImpl;
import es.in2.desmos.domain.models.*;
import es.in2.desmos.domain.services.policies.ReplicationPoliciesService;
import es.in2.desmos.objectmothers.DataNegotiationResultMother;
import es.in2.desmos.objectmothers.MVEntity4DataNegotiationMother;
import es.in2.desmos.objectmothers.MVEntityReplicationPoliciesInfoMother;
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
class DataNegotiationJobTests {
    @InjectMocks
    private DataNegotiationJobImpl dataNegotiationJob;

    @Mock
    private DataTransferJob dataTransferJob;

    @Mock
    private ReplicationPoliciesService replicationPoliciesService;

    @Captor
    private ArgumentCaptor<Mono<DataNegotiationResult>> dataNegotiationResultCaptor;

    @Captor
    private ArgumentCaptor<Mono<List<DataNegotiationResult>>> dataNegotiationResultsCaptor;

    @Test
    void itShouldSyncDataWithMultipleIssuers() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String processId = "0";

        Map<Issuer, List<MVEntity4DataNegotiation>> externalMVENtities4DataNegotiationByIssuer = new HashMap<>();
        Issuer issuer1 = new Issuer("http://example1.org");
        var externalMVEntitiesIssuer1 =
                List.of(MVEntity4DataNegotiationMother.sample1(),
                        MVEntity4DataNegotiationMother.sample2());
        externalMVENtities4DataNegotiationByIssuer.put(issuer1, externalMVEntitiesIssuer1);
        Issuer issuer2 = new Issuer("http://example2.org");
        var externalMVEntitiesIssuer2 =
                List.of(MVEntity4DataNegotiationMother.sample3(),
                        MVEntity4DataNegotiationMother.sample4());

        externalMVENtities4DataNegotiationByIssuer.put(issuer2, externalMVEntitiesIssuer2);

        Mono<List<MVEntity4DataNegotiation>> localEntityIdsMono = Mono.just(MVEntity4DataNegotiationMother.list1And2OldAnd3());

        when(dataTransferJob.syncDataFromList(eq(processId), any())).thenReturn(Mono.empty());

        when(replicationPoliciesService.isMVEntityReplicable(any(), any())).thenReturn(Mono.just(true));


        var result = dataNegotiationJob.negotiateDataSyncWithMultipleIssuers(processId, Mono.just(externalMVENtities4DataNegotiationByIssuer), localEntityIdsMono);

        StepVerifier
                .create(result)
                .verifyComplete();

        verify(dataTransferJob, times(1)).syncDataFromList(eq(processId), dataNegotiationResultsCaptor.capture());
        verifyNoMoreInteractions(dataTransferJob);

        Mono<List<DataNegotiationResult>> dataNegotiationResultsCaptured = dataNegotiationResultsCaptor.getValue();

        List<DataNegotiationResult> expectedDataNegotiationResults = DataNegotiationResultMother.listNewToSync2and4(issuer1.value(), issuer2.value());
        StepVerifier
                .create(dataNegotiationResultsCaptured)
                .consumeNextWith(captured -> assertThat(captured).hasSameElementsAs(expectedDataNegotiationResults))
                .verifyComplete();
    }

    @Test
    void itShouldSyncDataWithNewEntitiesToAdd() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String issuer = "http://example.org";
        Mono<String> issuerMono = Mono.just(issuer);

        List<MVEntity4DataNegotiation> externalEntityIds = MVEntity4DataNegotiationMother.fullList();
        Mono<List<MVEntity4DataNegotiation>> externalEntityIdsMono = Mono.just(externalEntityIds);

        Mono<List<MVEntity4DataNegotiation>> localEntityIdsMono = Mono.just(MVEntity4DataNegotiationMother.list3And4());

        String processId = "0";
        DataNegotiationEvent dataNegotiationEvent = new DataNegotiationEvent(processId, issuerMono, externalEntityIdsMono, localEntityIdsMono);

        List<MVEntity4DataNegotiation> expectedNewEntitiesToSync = MVEntity4DataNegotiationMother.list1And2();

        List<MVEntity4DataNegotiation> expectedExistingEntitiesToSync = new ArrayList<>();

        DataNegotiationResult expectedDataNegotiationResult = new DataNegotiationResult(issuer, expectedNewEntitiesToSync, expectedExistingEntitiesToSync);

        when(dataTransferJob.syncData(any(), any())).thenReturn(Mono.empty());

        when(replicationPoliciesService.isMVEntityReplicable(any(), any())).thenReturn(Mono.just(true));

        var result = dataNegotiationJob.negotiateDataSyncFromEvent(dataNegotiationEvent);

        StepVerifier
                .create(result)
                .verifyComplete();

        verify(dataTransferJob, times(1)).syncData(eq(processId), dataNegotiationResultCaptor.capture());
        verifyNoMoreInteractions(dataTransferJob);

        Mono<DataNegotiationResult> dataNegotiationResultCaptured = dataNegotiationResultCaptor.getValue();

        StepVerifier
                .create(dataNegotiationResultCaptured)
                .expectNext(expectedDataNegotiationResult)
                .verifyComplete();
    }

    @Test
    void itShouldSyncDataWithExistingEntitiesToAddWhenExternalVersionIsAfter() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String issuer = "http://example.org";
        Mono<String> issuerMono = Mono.just(issuer);

        List<MVEntity4DataNegotiation> externalEntityIds = MVEntity4DataNegotiationMother.list2And3();
        Mono<List<MVEntity4DataNegotiation>> externalEntityIdsMono = Mono.just(externalEntityIds);

        Mono<List<MVEntity4DataNegotiation>> localEntityIdsMono = Mono.just(List.of(MVEntity4DataNegotiationMother.sample2VersionOld()));

        String processId = "0";
        DataNegotiationEvent dataNegotiationEvent = new DataNegotiationEvent(processId, issuerMono, externalEntityIdsMono, localEntityIdsMono);

        List<MVEntity4DataNegotiation> expectedNewEntitiesToSync = List.of(MVEntity4DataNegotiationMother.sample3());

        List<MVEntity4DataNegotiation> expectedExistingEntitiesToSync = List.of(MVEntity4DataNegotiationMother.sample2());

        DataNegotiationResult expectedDataNegotiationResult = new DataNegotiationResult(issuer, expectedNewEntitiesToSync, expectedExistingEntitiesToSync);

        when(dataTransferJob.syncData(any(), any())).thenReturn(Mono.empty());

        when(replicationPoliciesService.isMVEntityReplicable(any(), any())).thenReturn(Mono.just(true));

        var result = dataNegotiationJob.negotiateDataSyncFromEvent(dataNegotiationEvent);

        StepVerifier
                .create(result)
                .verifyComplete();

        verify(dataTransferJob, times(1)).syncData(eq(processId), dataNegotiationResultCaptor.capture());
        verifyNoMoreInteractions(dataTransferJob);

        Mono<DataNegotiationResult> dataNegotiationResultCaptured = dataNegotiationResultCaptor.getValue();

        StepVerifier
                .create(dataNegotiationResultCaptured)
                .expectNext(expectedDataNegotiationResult)
                .verifyComplete();
    }

    @Test
    void itShouldSyncDataWithExistingEntitiesToAddWhenVersionIsEqualAndExternalLastUpdateIsAfter() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String issuer = "http://example.org";
        Mono<String> issuerMono = Mono.just(issuer);

        List<MVEntity4DataNegotiation> externalEntityIds = MVEntity4DataNegotiationMother.list2And3();
        Mono<List<MVEntity4DataNegotiation>> externalEntityIdsMono = Mono.just(externalEntityIds);

        Mono<List<MVEntity4DataNegotiation>> localEntityIdsMono = Mono.just(List.of(MVEntity4DataNegotiationMother.sample3TimestampOld()));

        String processId = "0";
        DataNegotiationEvent dataNegotiationEvent = new DataNegotiationEvent(processId, issuerMono, externalEntityIdsMono, localEntityIdsMono);

        List<MVEntity4DataNegotiation> expectedNewEntitiesToSync = List.of(MVEntity4DataNegotiationMother.sample2());

        List<MVEntity4DataNegotiation> expectedExistingEntitiesToSync = List.of(MVEntity4DataNegotiationMother.sample3());

        DataNegotiationResult expectedDataNegotiationResult = new DataNegotiationResult(issuer, expectedNewEntitiesToSync, expectedExistingEntitiesToSync);

        when(dataTransferJob.syncData(any(), any())).thenReturn(Mono.empty());

        when(replicationPoliciesService.isMVEntityReplicable(any(), any())).thenReturn(Mono.just(true));

        var result = dataNegotiationJob.negotiateDataSyncFromEvent(dataNegotiationEvent);

        StepVerifier
                .create(result)
                .verifyComplete();

        verify(dataTransferJob, times(1)).syncData(eq(processId), dataNegotiationResultCaptor.capture());
        verifyNoMoreInteractions(dataTransferJob);

        Mono<DataNegotiationResult> dataNegotiationResultCaptured = dataNegotiationResultCaptor.getValue();

        StepVerifier
                .create(dataNegotiationResultCaptured)
                .expectNext(expectedDataNegotiationResult)
                .verifyComplete();
    }

    @Test
    void itShouldNotSyncWhenVersionIsNewer() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String issuer = "http://example.org";
        Mono<String> issuerMono = Mono.just(issuer);

        List<MVEntity4DataNegotiation> externalEntityIds = List.of(MVEntity4DataNegotiationMother.sample2VersionOld());
        Mono<List<MVEntity4DataNegotiation>> externalEntityIdsMono = Mono.just(externalEntityIds);

        Mono<List<MVEntity4DataNegotiation>> localEntityIdsMono = Mono.just(List.of(MVEntity4DataNegotiationMother.sample2()));

        String processId = "0";
        DataNegotiationEvent dataNegotiationEvent = new DataNegotiationEvent(processId, issuerMono, externalEntityIdsMono, localEntityIdsMono);

        List<MVEntity4DataNegotiation> expectedNewEntitiesToSync = new ArrayList<>();

        List<MVEntity4DataNegotiation> expectedExistingEntitiesToSync = new ArrayList<>();

        DataNegotiationResult expectedDataNegotiationResult = new DataNegotiationResult(issuer, expectedNewEntitiesToSync, expectedExistingEntitiesToSync);

        when(dataTransferJob.syncData(any(), any())).thenReturn(Mono.empty());

        when(replicationPoliciesService.isMVEntityReplicable(any(), any())).thenReturn(Mono.just(true));

        var result = dataNegotiationJob.negotiateDataSyncFromEvent(dataNegotiationEvent);

        StepVerifier
                .create(result)
                .verifyComplete();

        verify(dataTransferJob, times(1)).syncData(eq(processId), dataNegotiationResultCaptor.capture());
        verifyNoMoreInteractions(dataTransferJob);

        Mono<DataNegotiationResult> dataNegotiationResultCaptured = dataNegotiationResultCaptor.getValue();

        StepVerifier
                .create(dataNegotiationResultCaptured)
                .expectNext(expectedDataNegotiationResult)
                .verifyComplete();
    }

    @Test
    void itShouldSyncOnlyValidEntities() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String issuer = "http://example.org";
        Mono<String> issuerMono = Mono.just(issuer);

        List<MVEntity4DataNegotiation> externalEntityIds = MVEntity4DataNegotiationMother.fullList();
        Mono<List<MVEntity4DataNegotiation>> externalEntityIdsMono = Mono.just(externalEntityIds);

        Mono<List<MVEntity4DataNegotiation>> localEntityIdsMono = Mono.just(MVEntity4DataNegotiationMother.list3And4());

        String processId = "0";
        DataNegotiationEvent dataNegotiationEvent = new DataNegotiationEvent(processId, issuerMono, externalEntityIdsMono, localEntityIdsMono);

        List<MVEntity4DataNegotiation> expectedNewEntitiesToSync = List.of(MVEntity4DataNegotiationMother.sample2());

        List<MVEntity4DataNegotiation> expectedExistingEntitiesToSync = new ArrayList<>();

        DataNegotiationResult expectedDataNegotiationResult = new DataNegotiationResult(issuer, expectedNewEntitiesToSync, expectedExistingEntitiesToSync);

        when(dataTransferJob.syncData(any(), any())).thenReturn(Mono.empty());

        when(replicationPoliciesService.isMVEntityReplicable(any(), any())).thenReturn(Mono.just(true));
        when(replicationPoliciesService.isMVEntityReplicable(any(), eq(MVEntityReplicationPoliciesInfoMother.sample1())))
                .thenReturn(Mono.just(false));

        var result = dataNegotiationJob.negotiateDataSyncFromEvent(dataNegotiationEvent);

        StepVerifier
                .create(result)
                .verifyComplete();

        verify(dataTransferJob, times(1)).syncData(eq(processId), dataNegotiationResultCaptor.capture());
        verifyNoMoreInteractions(dataTransferJob);

        Mono<DataNegotiationResult> dataNegotiationResultCaptured = dataNegotiationResultCaptor.getValue();

        StepVerifier
                .create(dataNegotiationResultCaptured)
                .expectNext(expectedDataNegotiationResult)
                .verifyComplete();
    }
}