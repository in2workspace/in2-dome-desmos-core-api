package es.in2.desmos.application.schedulers;

import es.in2.desmos.application.workflows.DataSyncWorkflow;
import es.in2.desmos.domain.exceptions.P2PDataSyncException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataSyncScheduler {

    private final DataSyncWorkflow dataSyncWorkflow;

    @Scheduled(cron = "0 0 2 * * *")
    public Flux<Void> initializeDataSync() {
        String processId = UUID.randomUUID().toString();

        return dataSyncWorkflow.startDataSyncWorkflow(processId)
                .doFirst(() -> log.info("ProcessID: {} - Starting Data Sync cron job", processId))
                .doOnError(error -> {
                    // An incomplete sync already logged each failed type and node; only the summary is repeated here
                    if (error instanceof P2PDataSyncException) {
                        log.warn("ProcessID: {} - Data Sync cron job incomplete: {}", processId, error.getMessage());
                    } else {
                        log.error("ProcessID: {} - Data Sync cron job failed", processId, error);
                    }
                })
                .doOnComplete(() -> log.info("ProcessID: {} - Data Sync cron job completed", processId))
                // Logged above; not propagated, otherwise Spring's reactive @Scheduled support logs it again with the
                // stack trace. Other errors keep propagating.
                .onErrorResume(P2PDataSyncException.class, error -> Flux.empty());
    }
}
