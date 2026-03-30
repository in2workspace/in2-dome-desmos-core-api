package es.in2.desmos.infrastructure.controllers;

import es.in2.desmos.application.workflows.jobs.P2PDataSyncJob;
import es.in2.desmos.domain.models.Entity;
import es.in2.desmos.domain.models.Id;
import es.in2.desmos.domain.models.MVEntity4DataNegotiation;
import es.in2.desmos.domain.models.Version;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

@WebFluxTest(DataSyncController.class)
@WithMockUser
@TestPropertySource(properties = "api.version=v2")
class DataSyncControllerTest {

    @MockBean
    private ApiConfig apiConfig;

    @MockBean
    private P2PDataSyncJob p2PDataSyncJob;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void getEntities_ValidId_Returns200AndExpectedEntities() {
        // Arrange
        String entityId = "urn:catalog:1";

        List<Entity> expectedEntities = List.of(
                new Entity("Entity details"),
                new Entity("Entity details 2")
        );

        when(p2PDataSyncJob.getLocalEntitiesByIdInBase64(anyString(), any()))
                .thenReturn(Mono.just(expectedEntities));

        // Act & Assert
        webTestClient
                .get()
                .uri("/api/v2/entities/{id}", entityId)
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer <token>")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Entity.class)
                .value(entities ->
                        assertThat(entities)
                                .hasSize(2)
                                .containsExactlyElementsOf(expectedEntities));

        verify(p2PDataSyncJob, times(1))
                .getLocalEntitiesByIdInBase64(anyString(), any());
    }

    @Test
    void discoverySync_ValidRequest_Returns200() {
        // Arrange
        String externalIssuer = "https://external-issuer.example.com";
        String expectedIssuer = "https://my-domain.example.com";

        MVEntity4DataNegotiation requestEntity = createMVEntity4DataNegotiation("urn:catalog:1");
        MVEntity4DataNegotiation responseEntity1 = createMVEntity4DataNegotiation("urn:catalog:1");
        MVEntity4DataNegotiation responseEntity2 = createMVEntity4DataNegotiation("urn:catalog:2");

        when(apiConfig.getExternalDomain()).thenReturn(expectedIssuer);
        when(p2PDataSyncJob.dataDiscovery(anyString(), any(), any()))
                .thenReturn(Flux.just(responseEntity1, responseEntity2));

        Flux<MVEntity4DataNegotiation> requestBody = Flux.just(requestEntity);

        // Act & Assert
        webTestClient
                .mutateWith(csrf())
                .post()
                .uri("/api/v2/sync/p2p/discovery")
                .header("X-Issuer", externalIssuer)
                .contentType(MediaType.APPLICATION_NDJSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer <token>")
                .body(requestBody, MVEntity4DataNegotiation.class)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Issuer", expectedIssuer);

        verify(apiConfig, times(1))
                .getExternalDomain();
        verify(p2PDataSyncJob, times(1))
                .dataDiscovery(anyString(), any(), any());
    }

    @Test
    void entitiesSync_ValidIds_Returns200AndExpectedEntities() {
        // Arrange
        Id[] requestIds = {
                new Id("urn:catalog:1"),
                new Id("urn:catalog:2")
        };

        List<Entity> expectedEntities = List.of(
                new Entity("Entity 1"),
                new Entity("Entity 2")
        );

        when(p2PDataSyncJob.getLocalEntitiesByIdInBase64(anyString(), any()))
                .thenReturn(Mono.just(expectedEntities));

        // Act & Assert
        webTestClient
                .mutateWith(csrf())
                .post()
                .uri("/api/v2/sync/p2p/entities")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer <token>")
                .bodyValue(requestIds)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Entity.class)
                .value(entities ->
                        assertThat(entities)
                                .hasSize(2)
                                .containsExactlyElementsOf(expectedEntities));

        verify(p2PDataSyncJob, times(1))
                .getLocalEntitiesByIdInBase64(anyString(), any());
    }

    private MVEntity4DataNegotiation createMVEntity4DataNegotiation(String id) {
        return new MVEntity4DataNegotiation(
                id, "Catalog", Version.from("1.0"), "2024-01-01T00:00:00Z",
                "Launched", "2024-01-01T00:00:00Z", "2025-01-01T00:00:00Z",
                "hash-" + id, "hashlink-" + id
        );
    }
}