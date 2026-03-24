package es.in2.desmos.application.schedulers;

import es.in2.desmos.application.workflows.DataSyncWorkflow;
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
                .doOnError(error -> log.error("ProcessID: {} - Data Sync cron job failed", processId, error))
                .doOnComplete(() -> log.info("ProcessID: {} - Data Sync cron job completed", processId));
    }
}
