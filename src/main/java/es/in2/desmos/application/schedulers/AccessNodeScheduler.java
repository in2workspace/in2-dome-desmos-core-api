package es.in2.desmos.application.schedulers;

import es.in2.desmos.infrastructure.configs.TrustFrameworkConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccessNodeScheduler {

    private final TrustFrameworkConfig trustFrameworkConfig;

    @Scheduled(cron = "0 */5 * * * *")
    public void getPublicKeyFromAccessNodeRepository() {
        String processId = UUID.randomUUID().toString();

        log.debug("ProcessID: {} - Starting cron Access Node Schedule cron job...", processId);

        trustFrameworkConfig.initialize().subscribe();
    }
}
