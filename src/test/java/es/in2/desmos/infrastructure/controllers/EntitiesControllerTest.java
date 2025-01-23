package es.in2.desmos.infrastructure.controllers;

import es.in2.desmos.domain.models.Entity;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import es.in2.desmos.domain.services.policies.PepWebClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(EntitiesController.class)
@WithMockUser
class EntitiesControllerTest {

    @MockBean
    private BrokerPublisherService brokerPublisherService;

    @MockBean
    private PepWebClient pepWebClient;

    @Autowired
    private WebTestClient webTestClient;


    @Test
    void testGetEntitiesSuccess() {
        String id = "urn:catalog:1";

        List<Entity> expectedEntitiesList = List.of(
                new Entity("Entity details"),
                new Entity("Entity details 2"));
        when(brokerPublisherService.findEntitiesAndItsSubentitiesByIdInBase64(anyString(), any(), any()))
                .thenReturn(Mono.just(expectedEntitiesList));

        when(pepWebClient.doRequest(any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());

        webTestClient
                .get()
                .uri("/api/v1/entities/{id}", id)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer <token>")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Entity.class)
                .isEqualTo(expectedEntitiesList);

        verify(pepWebClient).doRequest("/api/v1/entities/urn%3Acatalog%3A1", HttpMethod.GET, null, null, "Bearer <token>");
    }
}