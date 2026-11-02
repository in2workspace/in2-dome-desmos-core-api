package es.in2.desmos.testutils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Captures what a class logs, for tests that check log lines. Use with try-with-resources.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(Class<?> loggerClass) {
        this.logger = (Logger) LoggerFactory.getLogger(loggerClass);
        appender.start();
        logger.addAppender(appender);
    }

    public static LogCapture of(Class<?> loggerClass) {
        return new LogCapture(loggerClass);
    }

    public List<String> messages(Level level) {
        return appender.list.stream()
                .filter(event -> event.getLevel().equals(level))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
