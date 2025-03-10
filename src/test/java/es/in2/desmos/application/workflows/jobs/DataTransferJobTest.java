package es.in2.desmos.application.workflows.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.application.workflows.jobs.impl.DataTransferJobImpl;
import es.in2.desmos.domain.models.*;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.domain.services.sync.EntitySyncWebClient;
import es.in2.desmos.objectmothers.*;
import org.json.JSONException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.skyscreamer.jsonassert.JSONAssert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataTransferJobTest {
    @InjectMocks
    private DataTransferJobImpl dataTransferJob;

    @Mock
    private EntitySyncWebClient entitySyncWebClient;

    @Mock
    private DataPublicationJob dataPublicationJob;

    @Mock
    private AuditRecordService auditRecordService;

    @SuppressWarnings("CanBeFinal")
    @Spy
    private static ObjectMapper objectMapper = new ObjectMapper();

    @Captor
    private ArgumentCaptor<Mono<String>> monoIssuerCaptor;

    @Captor
    private ArgumentCaptor<MVAuditServiceEntity4DataNegotiation> mvAuditServiceCaptor;

    @Captor
    private ArgumentCaptor<Mono<Map<Id, Entity>>> idByEntityMonoCaptor;

    @Captor
    private ArgumentCaptor<Mono<Id[]>> entitySyncRequestCaptor;

    @Captor
    private ArgumentCaptor<Mono<List<MVEntity4DataNegotiation>>> mvEntities4DataNegotiationCaptor;

    @Captor
    private ArgumentCaptor<Mono<Map<Id, Entity>>> entitiesByIdCaptor;

    @Test
    void itShouldRequestEntitiesToExternalAccessNodeFromMultipleIssuers() throws IOException, JSONException, NoSuchAlgorithmException {
        String issuer1 = "https://example1.org";
        String issuer2 = "https://example2.org";

        List<DataNegotiationResult> dataNegotiationResults = DataNegotiationResultMother.listNewToSync2and4(issuer1, issuer2);

        Id[] entitySyncRequest1 = EntitySyncRequestMother.createFromDataNegotiationResult(dataNegotiationResults.get(0));
        Id[] entitySyncRequest2 = EntitySyncRequestMother.createFromDataNegotiationResult(dataNegotiationResults.get(1));

        Flux<String> entitySyncResponseMono2 = Flux.fromIterable(EntitySyncResponseMother.getSample2Base64());
        Flux<String> entitySyncResponseMono4 = Flux.fromIterable(EntitySyncResponseMother.getSample4Base64());

        String processId = "0";
        when(entitySyncWebClient.makeRequest(eq(processId), any(), any()))
                .thenAnswer(invocation -> {
                    Mono<String> issuerMono = invocation.getArgument(1);
                    String issuer = issuerMono.block();
                    if (Objects.equals(issuer, issuer1)) {
                        return entitySyncResponseMono2;
                    } else if (Objects.equals(issuer, issuer2)) {
                        return entitySyncResponseMono4;
                    } else {
                        return Mono.empty();
                    }
                });

        when(auditRecordService.buildAndSaveAuditRecordFromDataSync(any(), any(), any(), any())).thenReturn(Mono.empty());
        when(dataPublicationJob.verifyData(eq(processId), any(), any(), any(), any())).thenReturn(Mono.empty());

        Mono<Void> result = dataTransferJob.syncDataFromList(processId, Mono.just(dataNegotiationResults));

        StepVerifier.
                create(result)
                .verifyComplete();

        verify(entitySyncWebClient, times(2)).makeRequest(eq(processId), monoIssuerCaptor.capture(), entitySyncRequestCaptor.capture());
        verifyNoMoreInteractions(entitySyncWebClient);

        Mono<String> monoIssuerCaptured1 = monoIssuerCaptor.getAllValues().get(0);

        StepVerifier
                .create(monoIssuerCaptured1)
                .expectNext(dataNegotiationResults.get(0).issuer())
                .verifyComplete();

        Mono<Id[]> entitySyncRequestCaptured1 = entitySyncRequestCaptor.getAllValues().get(0);

        StepVerifier
                .create(entitySyncRequestCaptured1)
                .expectNextMatches(array -> Arrays.equals(entitySyncRequest1, array))
                .verifyComplete();

        Mono<String> monoIssuerCaptured2 = monoIssuerCaptor.getAllValues().get(1);

        StepVerifier
                .create(monoIssuerCaptured2)
                .expectNext(dataNegotiationResults.get(1).issuer())
                .verifyComplete();

        Mono<Id[]> entitySyncRequestCaptured2 = entitySyncRequestCaptor.getAllValues().get(1);

        StepVerifier
                .create(entitySyncRequestCaptured2)
                .expectNextMatches(array -> Arrays.equals(entitySyncRequest2, array))
                .verifyComplete();
    }


    @Test
    void itShouldRequestEntitiesToExternalAccessNode() throws IOException, JSONException, NoSuchAlgorithmException {
        DataNegotiationResult dataNegotiationResult = DataNegotiationResultMother.sample();
        Mono<DataNegotiationResult> dataNegotiationResultMono = Mono.just(dataNegotiationResult);

        Id[] entitySyncRequest =
                Stream.concat(
                                dataNegotiationResult.newEntitiesToSync().stream().map(x -> new Id(x.id())),
                                dataNegotiationResult.existingEntitiesToSync().stream().map(x -> new Id(x.id())))
                        .toArray(Id[]::new);

        Flux<String> entitySyncResponseMono = Flux.fromIterable(EntitySyncResponseMother.getSampleBase64());

        String processId = "0";
        when(entitySyncWebClient.makeRequest(eq(processId), any(), any())).thenReturn(entitySyncResponseMono);

        when(auditRecordService.buildAndSaveAuditRecordFromDataSync(any(), any(), any(), any())).thenReturn(Mono.empty());

        when(dataPublicationJob.verifyData(eq(processId), any(), any(), any(), any())).thenReturn(Mono.empty());

        Mono<Void> result = dataTransferJob.syncData(processId, dataNegotiationResultMono);

        StepVerifier.
                create(result)
                .verifyComplete();

        verify(entitySyncWebClient, times(1)).makeRequest(eq(processId), monoIssuerCaptor.capture(), entitySyncRequestCaptor.capture());
        verifyNoMoreInteractions(entitySyncWebClient);

        Mono<String> monoIssuerCaptured = monoIssuerCaptor.getValue();

        StepVerifier
                .create(monoIssuerCaptured)
                .expectNext(dataNegotiationResult.issuer())
                .verifyComplete();

        Mono<Id[]> entitySyncRequestCaptured = entitySyncRequestCaptor.getValue();

        StepVerifier
                .create(entitySyncRequestCaptured)
                .expectNextMatches(array -> Arrays.equals(entitySyncRequest, array))
                .verifyComplete();
    }

    @Test
    void itShouldPassOnlyValidEntitiesWhenHashIsIncorrect() throws IOException, JSONException, NoSuchAlgorithmException {
        DataNegotiationResult dataNegotiationResult = DataNegotiationResultMother.badHash();
        Mono<DataNegotiationResult> dataNegotiationResultMono = Mono.just(dataNegotiationResult);

        Flux<String> entitySyncResponseMono = Flux.fromIterable(EntitySyncResponseMother.getSampleBase64());

        String processId = "0";
        when(entitySyncWebClient.makeRequest(eq(processId), any(), any())).thenReturn(entitySyncResponseMono);

        when(auditRecordService.buildAndSaveAuditRecordFromDataSync(any(), any(), any(), any())).thenReturn(Mono.empty());

        when(dataPublicationJob.verifyData(eq(processId), any(), any(), any(), any())).thenReturn(Mono.empty());

        List<Id> expectedEntitiesById = List.of(
                new Id(MVEntity4DataNegotiationMother.sample2().id()),
                new Id(MVEntity4DataNegotiationMother.sample3().id()),
                new Id(MVEntity4DataNegotiationMother.sample4().id())
        );

        Mono<Void> result = dataTransferJob.syncData(processId, dataNegotiationResultMono);

        StepVerifier
                .create(result)
                .verifyComplete();

        verify(dataPublicationJob, times(1)).verifyData(any(), any(), entitiesByIdCaptor.capture(), any(), any());

        Mono<Map<Id, Entity>> entitiesByIdCaptured = entitiesByIdCaptor.getValue();

        StepVerifier.create(entitiesByIdCaptured)
                .assertNext(entitiesById -> {
                    List<Id> ids = entitiesById.keySet().stream().toList();
                    assertThat(ids).containsExactlyInAnyOrderElementsOf(expectedEntitiesById);
                })
                .verifyComplete();
    }

    @Test
    void itShouldBuildAllMVEntities4DataNegotiation() throws IOException, JSONException, NoSuchAlgorithmException {
        DataNegotiationResult dataNegotiationResult = DataNegotiationResultMother.sample();
        Mono<DataNegotiationResult> dataNegotiationResultMono = Mono.just(dataNegotiationResult);

        var allMVEntities4DataNegotiation = Stream.concat(dataNegotiationResult.newEntitiesToSync().stream(), dataNegotiationResult.existingEntitiesToSync().stream()).toList();
        List<MVEntity4DataNegotiation> expectedMVEntities4DataNegotiation = new ArrayList<>(allMVEntities4DataNegotiation);

        Flux<String> entitySyncResponseMono = Flux.fromIterable(EntitySyncResponseMother.getSampleBase64());

        String processId = "0";
        when(entitySyncWebClient.makeRequest(eq(processId), any(), any())).thenReturn(entitySyncResponseMono);

        when(auditRecordService.buildAndSaveAuditRecordFromDataSync(any(), any(), any(), any())).thenReturn(Mono.empty());

        when(dataPublicationJob.verifyData(eq(processId), any(), any(), any(), any())).thenReturn(Mono.empty());

        Mono<Void> result = dataTransferJob.syncData(processId, dataNegotiationResultMono);

        StepVerifier.
                create(result)
                .verifyComplete();

        verify(dataPublicationJob, times(1)).verifyData(eq(processId), any(), any(), mvEntities4DataNegotiationCaptor.capture(), any());
        verifyNoMoreInteractions(dataPublicationJob);

        Mono<List<MVEntity4DataNegotiation>> monoDataVerificationJobCaptured = mvEntities4DataNegotiationCaptor.getValue();

        StepVerifier
                .create(monoDataVerificationJobCaptured)
                .expectNext(expectedMVEntities4DataNegotiation)
                .verifyComplete();
    }

    @Test
    void itShouldNotDoDataTransferIfDataNegotiationResultIsEmpty() {
        DataNegotiationResult dataNegotiationResults = DataNegotiationResultMother.empty();

        String processId = "0";

        Mono<Void> result = dataTransferJob.syncData(processId, Mono.just(dataNegotiationResults));

        StepVerifier.
                create(result)
                .verifyComplete();

        verifyNoInteractions(entitySyncWebClient);
        verifyNoInteractions(dataPublicationJob);
        verifyNoInteractions(objectMapper);
    }

    @Test
    void itShouldCreateReceivedAuditRecord() throws JSONException, NoSuchAlgorithmException, IOException {
        String processId = "0";

        Flux<String> entitySyncResponseMono = Flux.fromIterable(EntitySyncResponseMother.getSampleBase64());

        when(entitySyncWebClient.makeRequest(eq(processId), any(), any())).thenReturn(entitySyncResponseMono);

        when(auditRecordService.buildAndSaveAuditRecordFromDataSync(any(), any(), any(), any())).thenReturn(Mono.empty());

        when(dataPublicationJob.verifyData(eq(processId), any(), any(), any(), any())).thenReturn(Mono.empty());

        DataNegotiationResult dataNegotiationResult = DataNegotiationResultMother.sample();

        var expectedMvAuditRecord = List.of(
                new MVAuditServiceEntity4DataNegotiation(MVEntity4DataNegotiationMother.sample1().id(), MVEntity4DataNegotiationMother.sample1().type(), "", ""),
                new MVAuditServiceEntity4DataNegotiation(MVEntity4DataNegotiationMother.sample2().id(), MVEntity4DataNegotiationMother.sample2().type(), "", ""),
                new MVAuditServiceEntity4DataNegotiation(MVEntity4DataNegotiationMother.sample3().id(), MVEntity4DataNegotiationMother.sample3().type(), "", ""),
                new MVAuditServiceEntity4DataNegotiation(MVEntity4DataNegotiationMother.sample4().id(), MVEntity4DataNegotiationMother.sample4().type(), "", ""));


        dataTransferJob.syncData(processId, Mono.just(dataNegotiationResult)).block();

        verify(auditRecordService, times(4))
                .buildAndSaveAuditRecordFromDataSync(
                        eq(processId),
                        eq(dataNegotiationResult.issuer()),
                        mvAuditServiceCaptor.capture(),
                        eq(AuditRecordStatus.RECEIVED));

        var mvAuditServiceCaptorResult = mvAuditServiceCaptor.getAllValues();

        assertThat(mvAuditServiceCaptorResult).isEqualTo(expectedMvAuditRecord);
    }

    @Test
    void itShouldFilterEntitiesWithBadHash() throws JSONException, NoSuchAlgorithmException, IOException {
        String processId = "0";

        Flux<String> entitySyncResponseMono = Flux.fromIterable(EntitySyncResponseMother.getSampleBase64());

        when(entitySyncWebClient.makeRequest(eq(processId), any(), any())).thenReturn(entitySyncResponseMono);

        when(auditRecordService.buildAndSaveAuditRecordFromDataSync(any(), any(), any(), any())).thenReturn(Mono.empty());

        when(dataPublicationJob.verifyData(eq(processId), any(), any(), any(), any())).thenReturn(Mono.empty());

        DataNegotiationResult dataNegotiationResult = DataNegotiationResultMother.sampleBadHash2();

        Map<Id, Entity> expectedFilteredEntities = Map.of(
                new Id(MVEntity4DataNegotiationMother.sample1().id()), new Entity(EntityMother.PRODUCT_OFFERING_1),
                new Id(MVEntity4DataNegotiationMother.sample3().id()), new Entity(EntityMother.PRODUCT_OFFERING_3),
                new Id(MVEntity4DataNegotiationMother.sample4().id()), new Entity(EntityMother.PRODUCT_OFFERING_4));


        dataTransferJob.syncData(processId, Mono.just(dataNegotiationResult)).block();

        verify(dataPublicationJob, times(1))
                .verifyData(
                        any(),
                        any(),
                        idByEntityMonoCaptor.capture(),
                        any(),
                        any()
                );

        StepVerifier
                .create(idByEntityMonoCaptor.getValue())
                .assertNext(idByEntityResult -> {
                    assertThat(idByEntityResult.keySet())
                            .isEqualTo(expectedFilteredEntities.keySet());
                    for (var id : idByEntityResult.keySet()) {
                        var expectedJson = expectedFilteredEntities.get(id).value();
                        var resultJson = idByEntityResult.get(id).value();
                        try {
                            JSONAssert.assertEquals(expectedJson, resultJson, true);
                        } catch (JSONException e) {
                            throw new RuntimeException(e);
                        }
                    }
                })
                .verifyComplete();
    }

    @Test
    void itShouldAddSubEntities() throws JSONException, NoSuchAlgorithmException, IOException {
        String processId = "0";

        Flux<String> entitySyncResponseMono = Flux.fromIterable(EntitySyncResponseMother.getSampleWithCategoryBase64());

        when(entitySyncWebClient.makeRequest(eq(processId), any(), any())).thenReturn(entitySyncResponseMono);

        when(auditRecordService.buildAndSaveAuditRecordFromDataSync(any(), any(), any(), any())).thenReturn(Mono.empty());

        when(dataPublicationJob.verifyData(eq(processId), any(), any(), any(), any())).thenReturn(Mono.empty());

        DataNegotiationResult dataNegotiationResult = DataNegotiationResultMother.sample();

        Map<Id, Entity> expectedFilteredEntities = Map.of(
                new Id(MVEntity4DataNegotiationMother.sample1().id()), new Entity(EntityMother.PRODUCT_OFFERING_1),
                new Id(MVEntity4DataNegotiationMother.sample2().id()), new Entity(EntityMother.PRODUCT_OFFERING_2),
                new Id(MVEntity4DataNegotiationMother.sample3().id()), new Entity(EntityMother.PRODUCT_OFFERING_3),
                new Id(MVEntity4DataNegotiationMother.sample4().id()), new Entity(EntityMother.PRODUCT_OFFERING_4),
                new Id("urn:category:1"), new Entity(EntityMother.CATEGORY));


        dataTransferJob.syncData(processId, Mono.just(dataNegotiationResult)).block();

        verify(dataPublicationJob, times(1))
                .verifyData(
                        any(),
                        any(),
                        idByEntityMonoCaptor.capture(),
                        any(),
                        any()
                );

        StepVerifier
                .create(idByEntityMonoCaptor.getValue())
                .assertNext(idByEntityResult -> {
                    assertThat(idByEntityResult.keySet())
                            .containsExactlyInAnyOrderElementsOf(expectedFilteredEntities.keySet());
                    for (var id : idByEntityResult.keySet()) {
                        var expectedJson = expectedFilteredEntities.get(id).value();
                        var resultJson = idByEntityResult.get(id).value();
                        try {
                            JSONAssert.assertEquals(expectedJson, resultJson, true);
                        } catch (JSONException e) {
                            throw new RuntimeException(e);
                        }
                    }
                })
                .verifyComplete();
    }
}