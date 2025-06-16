package es.in2.desmos.application.workflows.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import es.in2.desmos.application.workflows.jobs.impl.P2PDataSyncJobImpl;
import es.in2.desmos.domain.events.DataNegotiationEventPublisher;
import es.in2.desmos.domain.models.*;
import es.in2.desmos.domain.services.api.impl.AuditRecordServiceImpl;
import es.in2.desmos.domain.services.broker.impl.BrokerPublisherServiceImpl;
import es.in2.desmos.domain.services.policies.ReplicationPoliciesService;
import es.in2.desmos.domain.services.sync.DiscoverySyncWebClient;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.infrastructure.configs.ExternalAccessNodesConfig;
import es.in2.desmos.objectmothers.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class P2PDataSyncJobTests {
    @InjectMocks
    private P2PDataSyncJobImpl p2PDataSyncJob;

    @Mock
    private ExternalAccessNodesConfig externalAccessNodesConfig;

    @Mock
    private ApiConfig apiConfig;

    @Mock
    private BrokerPublisherServiceImpl brokerPublisherService;

    @Mock
    private AuditRecordServiceImpl auditRecordService;

    @Mock
    private DataNegotiationEventPublisher dataNegotiationEventPublisher;

    @Mock
    private DiscoverySyncWebClient discoverySyncWebClient;

    @Mock
    private DataNegotiationJob dataNegotiationJob;

    @Mock
    private ReplicationPoliciesService replicationPoliciesService;

    @Test
    void itShouldSynchronizeData() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String processId = "0";

        var brokerEntities = MVBrokerEntity4DataNegotiationMother.list3And4();
        var brokerCategories = MVBrokerEntity4DataNegotiationMother.listCategories();
        var brokerCatalogs = MVBrokerEntity4DataNegotiationMother.listCatalogs();

        when(brokerPublisherService.findAllIdTypeAndAttributesByType(
                eq(processId),
                anyString(),
                eq("lastUpdate"),
                eq("version"),
                eq("lifecycleStatus"),
                eq("validFor"),
                eq(BrokerEntityWithIdTypeLastUpdateAndVersion.class))
        ).thenAnswer(invocation -> {
            String entityType = invocation.getArgument(1);
            List<?> result = switch (entityType) {
                case MVEntity4DataNegotiationMother.PRODUCT_OFFERING_TYPE_NAME -> brokerEntities;
                case MVEntity4DataNegotiationMother.CATEGORY_TYPE_NAME -> brokerCategories;
                case MVEntity4DataNegotiationMother.CATALOG_TYPE_NAME -> brokerCatalogs;
                default -> Collections.emptyList();
            };

            return Flux.fromIterable(result);
        });

        when(auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(eq(processId), any(), any()))
                .thenAnswer(invocation -> {
                    String entityType = invocation.getArgument(1);
                    return switch (entityType) {
                        case MVEntity4DataNegotiationMother.PRODUCT_OFFERING_TYPE_NAME ->
                                Mono.just(MVAuditServiceEntity4DataNegotiationMother.sample3and4());
                        case MVEntity4DataNegotiationMother.CATEGORY_TYPE_NAME ->
                                Mono.just(MVAuditServiceEntity4DataNegotiationMother.listCategories());
                        case MVEntity4DataNegotiationMother.CATALOG_TYPE_NAME ->
                                Mono.just(MVAuditServiceEntity4DataNegotiationMother.listCatalogs());
                        default -> Mono.just(Collections.emptyList());
                    };
                });

        String myDomain = "http://my-domain.org";
        when(apiConfig.getExternalDomain()).thenReturn(myDomain);

        String externalDomain = "http://external-domain.org";
        List<MVEntity4DataNegotiation> sample3InList = List.of(MVEntity4DataNegotiationMother.sample3());
        DiscoverySyncResponse discoverySyncResponse3 = new DiscoverySyncResponse(externalDomain, sample3InList);

        List<MVEntity4DataNegotiation> sample4InList = List.of(MVEntity4DataNegotiationMother.sample4());
        DiscoverySyncResponse discoverySyncResponse4 = new DiscoverySyncResponse(externalDomain, sample4InList);

        when(discoverySyncWebClient.makeRequest(eq(processId), any(), any()))
                .thenReturn(Mono.just(discoverySyncResponse3))
                .thenReturn(Mono.just(discoverySyncResponse4));

        List<String> urlExternalAccessNodesList = UrlMother.example1And2urlsList();
        when(externalAccessNodesConfig.getExternalAccessNodesUrls()).thenReturn(Mono.just(urlExternalAccessNodesList));

        when(dataNegotiationJob.negotiateDataSyncWithMultipleIssuers(eq(processId), any(), any())).thenReturn(Mono.empty());

        List<MVEntityReplicationPoliciesInfo> mvEntityReplicationPoliciesInfoProductOfferings =
                brokerEntities
                        .stream()
                        .map(x ->
                                new MVEntityReplicationPoliciesInfo(
                                        x.getId(),
                                        x.getLifecycleStatus(),
                                        x.getValidFor()
                                                .startDateTime(),
                                        x.getValidFor()
                                                .endDateTime()))
                        .toList();
        var mvEntityReplicationPoliciesInfoProductOfferingsIdsFlux =
                Flux.fromIterable(mvEntityReplicationPoliciesInfoProductOfferings
                        .stream()
                        .map(x -> new Id(x.id()))
                        .toList());
        when(replicationPoliciesService.filterReplicableMvEntitiesList(
                processId,
                mvEntityReplicationPoliciesInfoProductOfferings
        )).thenReturn(mvEntityReplicationPoliciesInfoProductOfferingsIdsFlux);

        List<MVEntityReplicationPoliciesInfo> mvEntityReplicationPoliciesInfoCategories =
                brokerCategories
                        .stream()
                        .map(x ->
                                new MVEntityReplicationPoliciesInfo(
                                        x.getId(),
                                        x.getLifecycleStatus(),
                                        x.getValidFor()
                                                .startDateTime(),
                                        x.getValidFor()
                                                .endDateTime()))
                        .toList();
        var mvEntityReplicationPoliciesInfoCategoriesIdsFlux =
                Flux.fromIterable(mvEntityReplicationPoliciesInfoCategories
                        .stream()
                        .map(x -> new Id(x.id()))
                        .toList());
        when(replicationPoliciesService.filterReplicableMvEntitiesList(
                processId,
                mvEntityReplicationPoliciesInfoCategories
        )).thenReturn(mvEntityReplicationPoliciesInfoCategoriesIdsFlux);

        List<MVEntityReplicationPoliciesInfo> mvEntityReplicationPoliciesInfoCatalogs =
                brokerCatalogs
                        .stream()
                        .map(x ->
                                new MVEntityReplicationPoliciesInfo(
                                        x.getId(),
                                        x.getLifecycleStatus(),
                                        x.getValidFor()
                                                .startDateTime(),
                                        x.getValidFor()
                                                .endDateTime()))
                        .toList();
        var mvEntityReplicationPoliciesInfoCatalogsIdsFlux =
                Flux.fromIterable(mvEntityReplicationPoliciesInfoCatalogs
                        .stream()
                        .map(x -> new Id(x.id()))
                        .toList());
        when(replicationPoliciesService.filterReplicableMvEntitiesList(
                processId,
                mvEntityReplicationPoliciesInfoCatalogs
        )).thenReturn(mvEntityReplicationPoliciesInfoCatalogsIdsFlux);

        when(replicationPoliciesService.filterReplicableMvEntitiesList(processId, Collections.emptyList()))
                .thenReturn(Flux.empty());

        var result = p2PDataSyncJob.synchronizeData(processId);

        StepVerifier
                .create(result)
                .verifyComplete();

        verify(brokerPublisherService, times(1)).findAllIdTypeAndAttributesByType(processId, MVEntity4DataNegotiationMother.PRODUCT_OFFERING_TYPE_NAME, "lastUpdate", "version", "lifecycleStatus", "validFor", BrokerEntityWithIdTypeLastUpdateAndVersion.class);
        verifyNoMoreInteractions(brokerPublisherService);

        //Should be number of times as BROKER_ENTITY_TYPES lenght is
        verify(auditRecordService, atLeastOnce()).findCreateOrUpdateAuditRecordsByEntityIds(eq(processId), any(), any());
        verifyNoMoreInteractions(auditRecordService);
        verify(externalAccessNodesConfig, atLeastOnce()).getExternalAccessNodesUrls();
        verifyNoMoreInteractions(externalAccessNodesConfig);

        verify(apiConfig, atLeastOnce()).getExternalDomain();
        verifyNoMoreInteractions(apiConfig);

        verify(discoverySyncWebClient, atLeastOnce()).makeRequest(eq(processId), any(), any());
        verifyNoMoreInteractions(discoverySyncWebClient);

        verify(dataNegotiationJob, atLeastOnce()).negotiateDataSyncWithMultipleIssuers(eq(processId), any(), any());
        verifyNoMoreInteractions(dataNegotiationJob);
    }

    @Test
    void itShouldReturnInternalEntitiesWhenDiscovery() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        List<MVEntity4DataNegotiation> internalEntities = MVEntity4DataNegotiationMother.list3And4();
        internalEntities.addAll(MVEntity4DataNegotiationMother.listCategories());
        internalEntities.addAll(MVEntity4DataNegotiationMother.listCatalogs());

        String processId = "0";

        var brokerEntities = MVBrokerEntity4DataNegotiationMother.list3And4();
        var brokerCategories = MVBrokerEntity4DataNegotiationMother.listCategories();
        var brokerCatalogs = MVBrokerEntity4DataNegotiationMother.listCatalogs();

        when(brokerPublisherService.findAllIdTypeAndAttributesByType(
                eq(processId),
                anyString(),
                eq("lastUpdate"),
                eq("version"),
                eq("lifecycleStatus"),
                eq("validFor"),
                eq(BrokerEntityWithIdTypeLastUpdateAndVersion.class))
        ).thenAnswer(invocation -> {
            String entityType = invocation.getArgument(1);
            List<?> result = switch (entityType) {
                case MVEntity4DataNegotiationMother.PRODUCT_OFFERING_TYPE_NAME -> brokerEntities;
                case MVEntity4DataNegotiationMother.CATEGORY_TYPE_NAME -> brokerCategories;
                case MVEntity4DataNegotiationMother.CATALOG_TYPE_NAME -> brokerCatalogs;
                default -> Collections.emptyList();
            };
            return Flux.fromIterable(result);
        });

        when(auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(eq(processId), any(), any()))
                .thenAnswer(invocation -> {
                    String entityType = invocation.getArgument(1);
                    return switch (entityType) {
                        case MVEntity4DataNegotiationMother.PRODUCT_OFFERING_TYPE_NAME ->
                                Mono.just(MVAuditServiceEntity4DataNegotiationMother.sample3and4());
                        case MVEntity4DataNegotiationMother.CATEGORY_TYPE_NAME ->
                                Mono.just(MVAuditServiceEntity4DataNegotiationMother.listCategories());
                        case MVEntity4DataNegotiationMother.CATALOG_TYPE_NAME ->
                                Mono.just(MVAuditServiceEntity4DataNegotiationMother.listCatalogs());
                        default -> Mono.just(Collections.emptyList());
                    };
                });

        List<MVEntityReplicationPoliciesInfo> mvEntityReplicationPoliciesInfoProductOfferings =
                brokerEntities
                        .stream()
                        .map(x ->
                                new MVEntityReplicationPoliciesInfo(
                                        x.getId(),
                                        x.getLifecycleStatus(),
                                        x.getValidFor()
                                                .startDateTime(),
                                        x.getValidFor()
                                                .endDateTime()))
                        .toList();
        var mvEntityReplicationPoliciesInfoProductOfferingsIdsFlux =
                Flux.fromIterable(mvEntityReplicationPoliciesInfoProductOfferings
                        .stream()
                        .map(x -> new Id(x.id()))
                        .toList());
        when(replicationPoliciesService.filterReplicableMvEntitiesList(
                processId,
                mvEntityReplicationPoliciesInfoProductOfferings
        )).thenReturn(mvEntityReplicationPoliciesInfoProductOfferingsIdsFlux);

        List<MVEntityReplicationPoliciesInfo> mvEntityReplicationPoliciesInfoCategories =
                brokerCategories
                        .stream()
                        .map(x ->
                                new MVEntityReplicationPoliciesInfo(
                                        x.getId(),
                                        x.getLifecycleStatus(),
                                        x.getValidFor()
                                                .startDateTime(),
                                        x.getValidFor()
                                                .endDateTime()))
                        .toList();
        var mvEntityReplicationPoliciesInfoCategoriesIdsFlux =
                Flux.fromIterable(mvEntityReplicationPoliciesInfoCategories
                        .stream()
                        .map(x -> new Id(x.id()))
                        .toList());
        when(replicationPoliciesService.filterReplicableMvEntitiesList(
                processId,
                mvEntityReplicationPoliciesInfoCategories
        )).thenReturn(mvEntityReplicationPoliciesInfoCategoriesIdsFlux);

        List<MVEntityReplicationPoliciesInfo> mvEntityReplicationPoliciesInfoCatalogs =
                brokerCatalogs
                        .stream()
                        .map(x ->
                                new MVEntityReplicationPoliciesInfo(
                                        x.getId(),
                                        x.getLifecycleStatus(),
                                        x.getValidFor()
                                                .startDateTime(),
                                        x.getValidFor()
                                                .endDateTime()))
                        .toList();
        var mvEntityReplicationPoliciesInfoCatalogsIdsFlux =
                Flux.fromIterable(mvEntityReplicationPoliciesInfoCatalogs
                        .stream()
                        .map(x -> new Id(x.id()))
                        .toList());
        when(replicationPoliciesService.filterReplicableMvEntitiesList(
                processId,
                mvEntityReplicationPoliciesInfoCatalogs
        )).thenReturn(mvEntityReplicationPoliciesInfoCatalogsIdsFlux);

        when(replicationPoliciesService.filterReplicableMvEntitiesList(processId, Collections.emptyList()))
                .thenReturn(Flux.empty());

        Mono<List<MVEntity4DataNegotiation>> resultMono = p2PDataSyncJob.dataDiscovery(
                processId,
                Mono.just("https://example.org"),
                Mono.just(MVEntity4DataNegotiationMother.list1And2()));

        StepVerifier.create(resultMono)
                .consumeNextWith(result ->
                        assertThat(result).containsExactlyInAnyOrderElementsOf(internalEntities))
                .verifyComplete();

        verify(brokerPublisherService, times(1)).findAllIdTypeAndAttributesByType(processId, MVEntity4DataNegotiationMother.PRODUCT_OFFERING_TYPE_NAME, "lastUpdate", "version", "lifecycleStatus", "validFor", BrokerEntityWithIdTypeLastUpdateAndVersion.class);
        verifyNoMoreInteractions(brokerPublisherService);

        //Should be number of times as BROKER_ENTITY_TYPES lenght is
        verify(auditRecordService, times(15)).findCreateOrUpdateAuditRecordsByEntityIds(eq(processId), any(), any());
        verifyNoMoreInteractions(auditRecordService);
    }

    @Test
    void itShouldReturnLocalEntitiesWhenPassingId() throws JSONException {
        Mono<List<Id>> idsMono = Mono.just(Arrays.stream(IdMother.entitiesRequest).toList());

        String entityRequestBrokerJson = BrokerDataMother.GET_ENTITY_REQUEST_BROKER_JSON;
        JSONArray expectedResponseJsonArray = new JSONArray(entityRequestBrokerJson);
        List<Entity> expectedLocalEntities = new ArrayList<>();
        for (int i = 0; i < expectedResponseJsonArray.length(); i++) {
            String entity = expectedResponseJsonArray.getString(i);

            expectedLocalEntities.add(new Entity(entity));
        }
        Mono<List<Entity>> localEntitiesMono = Mono.just(expectedLocalEntities);

        String processId = "0";
        when(brokerPublisherService.findEntitiesAndItsSubentitiesByIdInBase64(eq(processId), any(), any())).thenReturn(localEntitiesMono);

        Mono<List<Entity>> result = p2PDataSyncJob.getLocalEntitiesByIdInBase64(processId, idsMono);

        System.out.println(expectedLocalEntities);

        StepVerifier
                .create(result)
                .assertNext(x -> assertThat(x).isEqualTo(expectedLocalEntities))
                .verifyComplete();

        verify(brokerPublisherService, times(1)).findEntitiesAndItsSubentitiesByIdInBase64(eq(processId), any(), any());
        verifyNoMoreInteractions(brokerPublisherService);
    }
}
