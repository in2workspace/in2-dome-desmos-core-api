package es.in2.desmos.infrastructure.controllers;

import es.in2.desmos.domain.services.sync.services.DataSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/backoffice/v1/actions")
@RequiredArgsConstructor
public class BackofficeController {

    private final DataSyncService dataSyncService;

    @GetMapping("/sync")
    public Mono<Void> synchronizeData() {
        String processId = UUID.randomUUID().toString();
        log.info("ProcessID: {} - Starting Data Synchronization...", processId);
        return dataSyncService.synchronizeData(processId);
    }

}
