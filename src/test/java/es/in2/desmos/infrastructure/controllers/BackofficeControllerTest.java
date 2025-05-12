package es.in2.desmos.infrastructure.controllers;

import es.in2.desmos.domain.services.sync.services.DataSyncService;
import es.in2.desmos.domain.utils.EndpointsConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@WebFluxTest(BackofficeController.class)
@WithMockUser
class BackofficeControllerTest {

    @MockBean
    private DataSyncService dataSyncService;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void testGetEntitiesSuccess() {
        when(dataSyncService.synchronizeData(anyString()))
                .thenReturn(Mono.empty());

        webTestClient
                .get()
                .uri(EndpointsConstants.P2P_DATA_SYNC)
                .header(HttpHeaders.AUTHORIZATION, "Bearer <token>")
                .exchange()
                .expectStatus().isOk();
    }
}