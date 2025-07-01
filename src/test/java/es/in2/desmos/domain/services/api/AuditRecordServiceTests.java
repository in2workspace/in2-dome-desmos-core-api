package es.in2.desmos.domain.services.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.models.*;
import es.in2.desmos.domain.repositories.AuditRecordRepository;
import es.in2.desmos.domain.services.api.impl.AuditRecordServiceImpl;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.objectmothers.AuditRecordMother;
import es.in2.desmos.objectmothers.EntityMother;
import es.in2.desmos.objectmothers.MVAuditServiceEntity4DataNegotiationMother;
import org.json.JSONException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditRecordServiceTests {

    @Mock
    private AuditRecordRepository auditRecordRepository;

    @Mock
    private BlockchainTxPayload blockchainTxPayload;

    @Mock
    private BrokerPublisherService brokerPublisherService;

    @Mock
    private ApiConfig apiConfig;

    @SuppressWarnings("CanBeFinal")
    @Spy
    private static ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AuditRecordServiceImpl auditRecordService;

    @Captor
    private ArgumentCaptor<AuditRecord> auditRecordArgumentCaptor;

    @Test
    void testBuildAndSaveAuditRecordFromBrokerNotification() throws Exception {
        // Arrange
        String processId = "processId";
        String sampleDataLocation = "http://localhost:8080/ngsi-ld/v1/entities/" +
                "urn:ngsi-ld:ProductOffering:38088145-aef3-440e-ab93-a33bc9bfce69" +
                "?hl=03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4";
        Map<String, Object> dataMap = new HashMap<>();
        dataMap.put("id", "entityId");
        dataMap.put("type", "entityType");
        AuditRecordStatus status = AuditRecordStatus.CREATED;
        AuditRecord lastAuditRecordRegistered = AuditRecord.builder()
                .hashLink("previousHashLink")
                .build();

        when(objectMapper.writeValueAsString(any())).thenReturn("sampleData");
        when(auditRecordRepository.findMostRecentAuditRecord()).thenReturn(Mono.just(lastAuditRecordRegistered));
        when(blockchainTxPayload.dataLocation()).thenReturn(sampleDataLocation);
        when(auditRecordRepository.save(any())).thenReturn(Mono.empty());

        // Act
        Mono<Void> result = auditRecordService.buildAndSaveAuditRecordFromBrokerNotification(processId, dataMap, status, blockchainTxPayload);

        // Assert
        StepVerifier.create(result)
                .verifyComplete();

        verify(auditRecordRepository, times(1)).save(any());
    }

    @Test
    void testBuildAndSaveAuditRecordFromBrokerNotification_BlockchainTxPayloadNull() throws JsonProcessingException {
        // Arrange
        String processId = "processId";
        Map<String, Object> dataMap = new HashMap<>();
        dataMap.put("id", "entityId");
        dataMap.put("type", "entityType");
        AuditRecordStatus status = AuditRecordStatus.CREATED;
        AuditRecord lastAuditRecordRegistered = AuditRecord.builder()
                .hashLink("previousHashLink")
                .build();

        when(objectMapper.writeValueAsString(any())).thenReturn("sampleData");
        when(auditRecordRepository.findMostRecentAuditRecord()).thenReturn(Mono.just(lastAuditRecordRegistered));
        when(auditRecordRepository.save(any())).thenReturn(Mono.empty());

        // Act
        auditRecordService.buildAndSaveAuditRecordFromBrokerNotification(processId, dataMap, status, null).block();

        // Assert
        verify(auditRecordRepository, times(1)).save(any());
    }

    @Test
    void testBuildAndSaveAuditRecordFromBrokerNotification_ExceptionHandling() throws Exception {
        // Arrange
        String processId = "processId";
        Map<String, Object> dataMap = new HashMap<>();
        dataMap.put("id", "entityId");
        dataMap.put("type", "entityType");
        AuditRecordStatus status = AuditRecordStatus.CREATED;
        AuditRecord lastAuditRecordRegistered = AuditRecord.builder()
                .hashLink("previousHashLink")
                .build();

        when(objectMapper.writeValueAsString(any())).thenThrow(JsonProcessingException.class);
        when(auditRecordRepository.findMostRecentAuditRecord()).thenReturn(Mono.just(lastAuditRecordRegistered));

        // Act
        Mono<Void> result = auditRecordService.buildAndSaveAuditRecordFromBrokerNotification(processId, dataMap, status, blockchainTxPayload);

        // Assert
        StepVerifier.create(result)
                .expectError(JsonProcessingException.class)
                .verify();
    }


    @Test
    void itShouldBuildAndSaveAuditRecordFromDataSync() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String processId = "0";
        String issuer = "http://example.org";
        MVAuditServiceEntity4DataNegotiation mvAuditServiceEntity4DataNegotiation = MVAuditServiceEntity4DataNegotiationMother.sample1();
        AuditRecordStatus status = AuditRecordStatus.RETRIEVED;
        AuditRecord expectedAuditRecord = AuditRecordMother.createAuditRecordFromMVAuditServiceEntity4DataNegotiation(mvAuditServiceEntity4DataNegotiation, status);

        when(auditRecordRepository.findMostRecentAuditRecord()).thenReturn(Mono.just(new AuditRecord()));
        when(auditRecordRepository.save(any())).thenReturn(Mono.just(expectedAuditRecord));

        var result = auditRecordService.buildAndSaveAuditRecordFromDataSync(processId, issuer, mvAuditServiceEntity4DataNegotiation, status);

        StepVerifier
                .create(result)
                .verifyComplete();

        verify(auditRecordRepository, times(1)).save(auditRecordArgumentCaptor.capture());
        verifyNoMoreInteractions(auditRecordRepository);

        assertThat(auditRecordArgumentCaptor.getValue())
                .usingRecursiveComparison()
                .ignoringFields("id", "createdAt", "hash", "hashLink")
                .isEqualTo(expectedAuditRecord);
    }

    @Test
    void testBuildAndSaveAuditRecordFromBlockchainNotification() throws Exception {
        // Arrange
        String processId = "processId";
        String sampleDataLocation = "http://localhost:8080/ngsi-ld/v1/entities/" +
                "urn:ngsi-ld:ProductOffering:38088145-aef3-440e-ab93-a33bc9bfce69" +
                "?hl=03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4";
        BlockchainNotification blockchainNotification = BlockchainNotification.builder()
                .dataLocation(sampleDataLocation)
                .eventType("entityType")
                .build();
        String retrievedBrokerEntity = "sampleData";
        AuditRecordStatus status = AuditRecordStatus.CREATED;
        AuditRecord lastAuditRecordRegistered = AuditRecord.builder()
                .hashLink("previousHashLink")
                .build();

        when(objectMapper.writeValueAsString(any())).thenReturn("sampleData");
        when(auditRecordRepository.findMostRecentAuditRecord()).thenReturn(Mono.just(lastAuditRecordRegistered));
        when(auditRecordRepository.save(any())).thenReturn(Mono.empty());

        // Act
        Mono<Void> result = auditRecordService.buildAndSaveAuditRecordFromBlockchainNotification(processId, blockchainNotification, retrievedBrokerEntity, status);

        // Assert
        StepVerifier.create(result)
                .verifyComplete();

        verify(auditRecordRepository, times(1)).save(any());
    }

    @Test
    void testBuildAndSaveAuditRecordFromBlockchainNotification_NullOrEmptyRetrievedEntity() throws JsonProcessingException {
        // Arrange
        String processId = "processId";
        String entityId = "urn:ngsi-ld:ProductOffering:38088145-aef3-440e-ab93-a33bc9bfce69";
        String entityHashLink = "dbff5341acad5e2a58db4efd5e72e2d9a0a843a28e02b1183c68162d0a3a3de6";
        String dataLocation = "http://localhost:8080/ngsi-ld/v1/entities/" + entityId + "?hl=" + entityHashLink;
        BlockchainNotification blockchainNotification = BlockchainNotification.builder()
                .dataLocation(dataLocation).build();
        AuditRecordStatus auditRecordStatus = AuditRecordStatus.RETRIEVED;
        AuditRecord lastAuditRecordRegistered = AuditRecord.builder()
                .hashLink("previousHashLink")
                .build();

        when(objectMapper.writeValueAsString(any())).thenReturn("sampleData");
        when(auditRecordRepository.findMostRecentAuditRecord()).thenReturn(Mono.just(lastAuditRecordRegistered));
        when(auditRecordRepository.save(any())).thenReturn(Mono.empty());

        // Act
        auditRecordService.buildAndSaveAuditRecordFromBlockchainNotification(processId, blockchainNotification, null, auditRecordStatus).block();

        // Assert
        verify(auditRecordRepository, times(1)).save(argThat(auditRecord ->
                auditRecord.getEntityHash().isEmpty() && auditRecord.getEntityHashLink().isEmpty()));
    }

    @Test
    void testBuildAndSaveAuditRecordFromBlockchainNotification_ExceptionHandling() throws JsonProcessingException {
        // Arrange
        String processId = "processId";
        String entityId = "urn:ngsi-ld:ProductOffering:38088145-aef3-440e-ab93-a33bc9bfce69";
        String entityHashLink = "dbff5341acad5e2a58db4efd5e72e2d9a0a843a28e02b1183c68162d0a3a3de6";
        String dataLocation = "http://localhost:8080/ngsi-ld/v1/entities/" + entityId + "?hl=" + entityHashLink;
        BlockchainNotification blockchainNotification = BlockchainNotification.builder()
                .dataLocation(dataLocation).build();
        AuditRecordStatus status = AuditRecordStatus.RETRIEVED;
        AuditRecord lastAuditRecordRegistered = AuditRecord.builder()
                .hashLink("previousHashLink")
                .build();

        when(objectMapper.writeValueAsString(any())).thenThrow(JsonProcessingException.class);
        when(auditRecordRepository.findMostRecentAuditRecord()).thenReturn(Mono.just(lastAuditRecordRegistered));

        // Act
        Mono<Void> result = auditRecordService.buildAndSaveAuditRecordFromBlockchainNotification(processId, blockchainNotification, "retrievedBrokerEntity", status);

        // Assert
        StepVerifier.create(result)
                .expectError(JsonProcessingException.class)
                .verify();
    }

    @Test
    void testFetchLatestProducerEntityHashLinkByEntityId() {
        //Arrange
        String processId = "processId";
        String entityId = "entityId";
        AuditRecord auditRecord = AuditRecord.builder()
                .entityHash("entityHash")
                .entityHashLink("entityHashLink")
                .build();
        when(auditRecordService.getLastPublishedAuditRecordForProducerByEntityId(processId, entityId)).thenReturn(Mono.just(auditRecord));
        // Act
        String actualEntityHashLink = auditRecordService.fetchLatestProducerEntityHashLinkByEntityId(processId, entityId).block();
        // Assert
        assertEquals(auditRecord.getEntityHashLink(), actualEntityHashLink);
    }

    @Test
    void testFindLatestConsumerPublishedAuditRecordByEntityId() {
        // Arrange
        String processId = "processId";
        String entityId = "entityId";
        AuditRecord auditRecord = new AuditRecord();
        auditRecord.setEntityHash("entityHash");
        when(auditRecordRepository.findLastPublishedConsumerAuditRecordByEntityId(entityId)).thenReturn(Mono.just(auditRecord));
        // Act
        AuditRecord actualAuditRecord = auditRecordService.findLatestConsumerPublishedAuditRecordByEntityId(processId, entityId).block();
        // Assert
        assertEquals(auditRecord, actualAuditRecord);
    }

    @Test
    void testFindLatestConsumerPublishedAuditRecord() {
        // Arrange
        String processId = "processId";
        AuditRecord auditRecord = new AuditRecord();
        auditRecord.setEntityHash("entityHash");
        when(auditRecordRepository.findLastPublishedConsumerAuditRecord()).thenReturn(Mono.just(auditRecord));
        // Act
        AuditRecord actualAuditRecord = auditRecordService.findLatestConsumerPublishedAuditRecord(processId).block();
        // Assert
        assertEquals(auditRecord, actualAuditRecord);
    }

    @Test
    void testFindLatestAuditRecordForEntity() {
        // Arrange
        String processId = "processId";
        String entityId = "entityId";
        AuditRecord auditRecord = new AuditRecord();
        auditRecord.setEntityHash("entityHash");
        when(auditRecordRepository.findMostRecentRetrievedOrDeletedByEntityId(entityId)).thenReturn(Mono.just(auditRecord));
        // Act
        AuditRecord actualAuditRecord = auditRecordService.findMostRecentRetrievedOrDeletedByEntityId(processId, entityId).block();
        // Assert
        assertEquals(auditRecord, actualAuditRecord);
    }

    @Test
    void testFetchMostRecentAuditRecord_EmptyTable() {
        // Arrange
        when(auditRecordRepository.findMostRecentAuditRecord()).thenReturn(Mono.empty());
        when(auditRecordRepository.count()).thenReturn(Mono.just(0L));

        // Act
        auditRecordService.fetchMostRecentAuditRecord().block();

        // Assert
        verify(auditRecordRepository, times(1)).findMostRecentAuditRecord();
    }

    @Test
    void testFetchMostRecentAuditRecord_NonEmptyTable_NoRecentRecord() {
        // Arrange
        when(auditRecordRepository.findMostRecentAuditRecord()).thenReturn(Mono.empty());
        when(auditRecordRepository.count()).thenReturn(Mono.just(1L));
        // Act
        Mono<AuditRecord> mono = auditRecordService.fetchMostRecentAuditRecord();
        // Assert
        assertThrows(NoSuchElementException.class, mono::block);
        // Verify
        verify(auditRecordRepository, times(1)).findMostRecentAuditRecord();
    }


    @Test
    void itShouldReturnErrorWhenAuditRecordCreatesIncorrectJson() throws JsonProcessingException, JSONException, NoSuchAlgorithmException {
        String processId = "0";
        String issuer = "http://example.org";
        MVAuditServiceEntity4DataNegotiation mvAuditServiceEntity4DataNegotiation = MVAuditServiceEntity4DataNegotiationMother.sample1();
        AuditRecordStatus status = AuditRecordStatus.RETRIEVED;

        when(auditRecordRepository.findMostRecentAuditRecord()).thenReturn(Mono.just(new AuditRecord()));

        when(objectMapper.writeValueAsString(any())).thenThrow(JsonProcessingException.class);

        var result = auditRecordService.buildAndSaveAuditRecordFromDataSync(processId, issuer, mvAuditServiceEntity4DataNegotiation, status);

        StepVerifier
                .create(result)
                .expectErrorMatches(JsonProcessingException.class::isInstance)
                .verify();
    }

    @Test
    void itShouldReturnExistingAuditRecordIfEntityHasAuditRecordAndHashIsEqualsToCurrentEntityHash() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String processId = "0";
        var productOffering3Mono = Mono.just(EntityMother.PRODUCT_OFFERING_3);
        var productOffering4Mono = Mono.just(EntityMother.PRODUCT_OFFERING_4);
        when(brokerPublisherService.getEntityById(eq(processId), any()))
                .thenReturn(productOffering3Mono)
                .thenReturn(productOffering4Mono);

        when(auditRecordRepository.findMostRecentPublishedAuditRecordsByEntityIds(any()))
                .thenReturn(Flux.just(AuditRecordMother.list3And4().get(0), AuditRecordMother.list3And4().get(1)));

        var expectedAuditEntities = MVAuditServiceEntity4DataNegotiationMother.sample3and4();
        var entityIdsFlux = Flux.fromIterable(List.of(expectedAuditEntities.get(0).id(), expectedAuditEntities.get(1).id()));

        Flux<MVAuditServiceEntity4DataNegotiation> resultFlux =
                auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(processId, expectedAuditEntities.get(0).type(), entityIdsFlux);

        StepVerifier
                .create(resultFlux.collectList())
                .assertNext(result ->
                        assertThat(result).isEqualTo(expectedAuditEntities))
                .verifyComplete();
    }

    @Test
    void itShouldNotCreateAuditRecordIfEntityHasAuditRecordAndHashIsEqualsToCurrentEntityHash() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String processId = "0";
        var productOffering3Mono = Mono.just(EntityMother.PRODUCT_OFFERING_3);
        var productOffering4Mono = Mono.just(EntityMother.PRODUCT_OFFERING_4);
        when(brokerPublisherService.getEntityById(eq(processId), any()))
                .thenReturn(productOffering3Mono)
                .thenReturn(productOffering4Mono);

        when(auditRecordRepository.findMostRecentPublishedAuditRecordsByEntityIds(any()))
                .thenReturn(
                        Flux.just(
                                AuditRecordMother.list3And4().get(0),
                                AuditRecordMother.list3And4().get(1)));

        var expectedAuditEntities = MVAuditServiceEntity4DataNegotiationMother.sample3and4();
        var entityIdsMono = Flux.fromIterable(List.of(expectedAuditEntities.get(0).id(), expectedAuditEntities.get(1).id()));

        Flux<MVAuditServiceEntity4DataNegotiation> resultMono =
                auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(processId, expectedAuditEntities.get(0).type(), entityIdsMono);

        StepVerifier
                .create(resultMono.collectList())
                .expectNextCount(1)
                .verifyComplete();

        verify(auditRecordRepository, never()).save(any());
        verifyNoMoreInteractions(auditRecordRepository);
    }

    @Test
    void itShouldReturnNewAuditRecordIfEntityHasAuditRecordAndHashIsNotEqualsToCurrentEntityHash() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String processId = "0";
        var productOffering3Mono = Mono.just(EntityMother.PRODUCT_OFFERING_3);
        var productOffering4Mono = Mono.just(EntityMother.PRODUCT_OFFERING_4);
        when(brokerPublisherService.getEntityById(eq(processId), any()))
                .thenReturn(productOffering3Mono)
                .thenReturn(productOffering4Mono);

        var auditRecord3Mono = Mono.just(AuditRecordMother.list3OtherHashTraderProducerAnd4().get(0));

        when(auditRecordRepository.findMostRecentPublishedAuditRecordsByEntityIds(any()))
                .thenReturn(
                        Flux.just(
                                AuditRecordMother.list3OtherHashTraderProducerAnd4().get(0),
                                AuditRecordMother.list3OtherHashTraderProducerAnd4().get(1)));

        when(auditRecordRepository.findMostRecentAuditRecord())
                .thenReturn(Mono.just(new AuditRecord()));

        when(auditRecordRepository.save(any()))
                .thenReturn(auditRecord3Mono);

        var expectedAuditEntities = MVAuditServiceEntity4DataNegotiationMother.sample3and4NewHashlink();
        var entityIdsMono = Flux.fromIterable(List.of(expectedAuditEntities.get(0).id(), expectedAuditEntities.get(1).id()));

        Flux<MVAuditServiceEntity4DataNegotiation> resultMono =
                auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(processId, expectedAuditEntities.get(0).type(), entityIdsMono);

        StepVerifier
                .create(resultMono.collectList())
                .assertNext(result ->
                        assertThat(result).isEqualTo(expectedAuditEntities))
                .verifyComplete();
    }

    @Test
    void itShouldCreateNewAuditRecordWithTraderConsumerAndDataLocationEmptyIfEntityHasAuditRecordAndHashIsNotEqualsToCurrentEntityHashAndTraderIsConsumer() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {

        String processId = "0";
        var expectedAuditEntities = MVAuditServiceEntity4DataNegotiationMother.sample3and4NewHashlink();

       Flux<String> entityIdsMono = Flux.fromIterable(List.of(expectedAuditEntities.get(0).id(), expectedAuditEntities.get(1).id()));

        when(brokerPublisherService.getEntityById(eq(processId), any()))
                .thenReturn(Mono.just(EntityMother.PRODUCT_OFFERING_3))
                .thenReturn(Mono.just(EntityMother.PRODUCT_OFFERING_4));

        when(auditRecordRepository.findMostRecentPublishedAuditRecordsByEntityIds(any()))
                .thenReturn(
                        Flux.just(
                                AuditRecordMother.list3OtherHashWithTraderConsumerAnd4().get(0),
                                AuditRecordMother.list3OtherHashWithTraderConsumerAnd4().get(1)));

        when(auditRecordRepository.findMostRecentAuditRecord())
                .thenReturn(Mono.just(new AuditRecord()));

        when(auditRecordRepository.save(any()))
                .thenReturn(Mono.just(AuditRecordMother.list3OtherHashWithTraderConsumerAnd4().get(0)));

        Flux<MVAuditServiceEntity4DataNegotiation> resultMono =
                auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(processId, expectedAuditEntities.get(0).type(), entityIdsMono);

        StepVerifier
                .create(resultMono.collectList())
                .expectNextCount(1)
                .verifyComplete();

        verify(auditRecordRepository, times(1)).save(auditRecordArgumentCaptor.capture());
        verifyNoMoreInteractions(auditRecordRepository);

        var auditRecordSaved = auditRecordArgumentCaptor.getValue();
        assertThat(auditRecordSaved)
                .usingRecursiveComparison()
                .comparingOnlyFields("entityId", "entityType", "entityHash", "entityHashLink", "trader", "dataLocation")
                .isEqualTo(
                        AuditRecord
                                .builder()
                                .entityId(expectedAuditEntities.get(0).id())
                                .entityType(expectedAuditEntities.get(0).type())
                                .entityHash(expectedAuditEntities.get(0).hash())
                                .entityHashLink(expectedAuditEntities.get(0).hashlink())
                                .trader(AuditRecordTrader.CONSUMER)
                                .dataLocation(""));
    }

    @Test
    void itShouldCreateAndReturnAuditRecordIfEntityHasNotAuditRecord() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {

        String processId = "0";
        var expectedAuditEntities = MVAuditServiceEntity4DataNegotiationMother.sample3EqualsHashAndHashlinkAnd4();

        Flux<String> entityIdsMono = Flux.fromIterable(List.of(expectedAuditEntities.get(0).id(), expectedAuditEntities.get(1).id()));

        when(brokerPublisherService.getEntityById(eq(processId), any()))
                .thenReturn(Mono.just(EntityMother.PRODUCT_OFFERING_3))
                .thenReturn(Mono.just(EntityMother.PRODUCT_OFFERING_4));

        when(auditRecordRepository.findMostRecentPublishedAuditRecordsByEntityIds(any()))
                .thenReturn(Flux.just(AuditRecordMother.list3And4().get(1)));

        when(auditRecordRepository.findMostRecentAuditRecord())
                .thenReturn(Mono.just(new AuditRecord()));

        when(auditRecordRepository.findMostRecentAuditRecord())
                .thenReturn(Mono.just(new AuditRecord()));

        when(apiConfig.getExternalDomain())
                .thenReturn("http://my-external-domain.org");

        when(auditRecordRepository.save(any()))
                .thenReturn(Mono.just(AuditRecordMother.list3EqualsHashAndHashLinkAnd4().get(0)));

        Flux<MVAuditServiceEntity4DataNegotiation> resultMono =
                auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(processId, expectedAuditEntities.get(0).type(), entityIdsMono);

        StepVerifier
                .create(resultMono.collectList())
                .assertNext(result ->
                        assertThat(result).isEqualTo(expectedAuditEntities))
                .verifyComplete();

        verify(auditRecordRepository, times(1)).save(auditRecordArgumentCaptor.capture());
        verifyNoMoreInteractions(auditRecordRepository);

        var auditRecordSaved = auditRecordArgumentCaptor.getValue();
        assertThat(auditRecordSaved)
                .satisfies(auditRecord -> {
                    assertThat(auditRecord.getDataLocation())
                            .isNotNull()
                            .isNotBlank();

                    assertThat(auditRecordSaved)
                            .usingRecursiveComparison()
                            .comparingOnlyFields("entityId", "entityType", "entityHash", "entityHashLink", "trader")
                            .isEqualTo(
                                    AuditRecord
                                            .builder()
                                            .entityId(expectedAuditEntities.get(0).id())
                                            .entityType(expectedAuditEntities.get(0).type())
                                            .entityHash(expectedAuditEntities.get(0).hash())
                                            .entityHashLink(expectedAuditEntities.get(0).hash())
                                            .trader(AuditRecordTrader.PRODUCER));
                });
    }

    @Test
    void testBuildAndSaveAuditRecordForSubEntity() {
        String processId = "testProcessId";
        String entityId = "testEntityId";
        String entityType = "testEntityType";
        String retrievedBrokerEntity = "testRetrievedBrokerEntity";
        AuditRecordStatus status = AuditRecordStatus.RETRIEVED;

        AuditRecord lastAuditRecord = AuditRecord.builder()
                .id(UUID.randomUUID())
                .processId(processId)
                .createdAt(Timestamp.from(Instant.now()))
                .entityId(entityId)
                .entityType(entityType)
                .entityHash("previousHash")
                .entityHashLink("previousHashLink")
                .dataLocation("")
                .status(AuditRecordStatus.PUBLISHED)
                .trader(AuditRecordTrader.CONSUMER)
                .hash("previousHash")
                .hashLink("previousHashLink")
                .newTransaction(true)
                .build();

        when(auditRecordRepository.findMostRecentAuditRecord()).thenReturn(Mono.just(lastAuditRecord));
        when(auditRecordRepository.findLastPublishedConsumerAuditRecordByEntityId(anyString())).thenReturn(Mono.just(lastAuditRecord));
        when(auditRecordRepository.save(any(AuditRecord.class))).thenReturn(Mono.just(lastAuditRecord));

        Mono<Void> result = auditRecordService.buildAndSaveAuditRecordForSubEntity(processId, entityId, entityType, retrievedBrokerEntity, status);

        StepVerifier.create(result)
                .verifyComplete();

        verify(auditRecordRepository, times(1)).save(any(AuditRecord.class));
    }
}