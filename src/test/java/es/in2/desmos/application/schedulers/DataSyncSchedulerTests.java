package es.in2.desmos.application.schedulers;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import es.in2.desmos.application.workflows.DataSyncWorkflow;
import es.in2.desmos.domain.exceptions.P2PDataSyncException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataSyncSchedulerTests {
    @InjectMocks
    DataSyncScheduler dataSyncScheduler;

    @Mock
    DataSyncWorkflow dataSyncWorkflow;

    @Test
    void itShouldCallStartDataSyncWorkflow() {
        when(dataSyncWorkflow.startDataSyncWorkflow(anyString())).thenReturn(Flux.empty());

        Flux<Void> result = dataSyncScheduler.initializeDataSync();

        StepVerifier
                .create(result)
                .verifyComplete();

        verify(dataSyncWorkflow, times(1)).startDataSyncWorkflow(any());
        verifyNoMoreInteractions(dataSyncWorkflow);
    }

    @Test
    void itShouldLogAnIncompleteSyncAsWarningWithoutStackTrace() {
        when(dataSyncWorkflow.startDataSyncWorkflow(anyString()))
                .thenReturn(Flux.error(new P2PDataSyncException("P2P Data Synchronization incomplete: 1 of 1 genesis nodes failed")));
        Logger logger = (Logger) LoggerFactory.getLogger(DataSyncScheduler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            StepVerifier
                    .create(dataSyncScheduler.initializeDataSync())
                    .verifyComplete();

            assertThat(appender.list)
                    .filteredOn(event -> event.getLevel().equals(Level.WARN))
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.getFormattedMessage()).contains("Data Sync cron job incomplete: P2P Data Synchronization incomplete");
                        assertThat(event.getThrowableProxy()).isNull();
                    });
            assertThat(appender.list).noneMatch(event -> event.getLevel().equals(Level.ERROR));
            assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains("cron job completed"));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
