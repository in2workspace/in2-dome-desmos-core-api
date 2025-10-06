package es.in2.desmos.infrastructure.controllers;

import es.in2.desmos.domain.services.sync.services.DataSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static es.in2.desmos.domain.utils.EndpointsConstants.BACKOFFICE_P2P_DATA_SYNC;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@WebFluxTest(BackofficeController.class)
@WithMockUser
@TestPropertySource(properties = "api.version=v2")
class BackofficeControllerTest {

    @MockBean
    private DataSyncService dataSyncService;

    @Autowired
    private WebTestClient webTestClient;

    private String backofficeNotificationEndpoint;

    @BeforeEach
    void setUp() {
        backofficeNotificationEndpoint = "/backoffice/v2"+BACKOFFICE_P2P_DATA_SYNC;
    }

    @Test
    void testGetEntitiesSuccess() {
        when(dataSyncService.synchronizeData(anyString()))
                .thenReturn(Mono.empty());

        webTestClient
                .get()
                .uri(backofficeNotificationEndpoint)
                .header(HttpHeaders.AUTHORIZATION, "Bearer <token>")
                .exchange()
                .expectStatus().isOk();
    }
}