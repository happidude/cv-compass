package com.cvcompass.backend.mapping.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cvcompass.backend.mapping.service.MappingService;
import com.cvcompass.backend.mapping.service.MappingWorker;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class MappingSchedulerTests {
    @Test
    void scheduledFailuresLogOnlySafeEventWithoutThrowableOrSource() {
        var mapping = mock(MappingService.class);
        var worker = mock(MappingWorker.class);
        var failure = new IllegalStateException("SYNTHETIC_PRIVATE_DIAGNOSTIC");
        when(mapping.scan(100)).thenThrow(failure);
        when(mapping.reap(100)).thenThrow(failure);
        when(worker.runOne()).thenThrow(failure);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger("com.cvcompass.events");
        logger.addAppender(appender);
        try {
            var scheduler = new MappingScheduler(mapping, worker);
            scheduler.scan();
            scheduler.work();
            scheduler.recover();
            assertThat(appender.list)
                    .hasSize(3)
                    .allSatisfy(
                            event -> {
                                assertThat(event.getFormattedMessage())
                                        .isEqualTo(
                                                "MAPPING_SCHEDULER_FAILED exceptionType=IllegalStateException");
                                assertThat(event.getThrowableProxy()).isNull();
                                assertThat(event.getFormattedMessage())
                                        .doesNotContain("SYNTHETIC_PRIVATE_DIAGNOSTIC");
                            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
