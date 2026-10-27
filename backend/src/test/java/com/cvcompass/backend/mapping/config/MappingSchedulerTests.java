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
    void scheduledFailuresLogOnlySafeEventWithoutThrowableOrSource() throws Exception {
        var mapping = mock(MappingService.class);
        var worker = mock(MappingWorker.class);
        var failure = new IllegalStateException("SYNTHETIC_PRIVATE_DIAGNOSTIC");
        when(mapping.scan()).thenThrow(failure);
        when(mapping.reap()).thenThrow(failure);
        var entered = new java.util.concurrent.CountDownLatch(1);
        when(worker.runOne())
                .thenAnswer(
                        call -> {
                            entered.countDown();
                            throw failure;
                        });
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger("com.cvcompass.events");
        logger.addAppender(appender);
        try {
            var scheduler = new MappingScheduler(mapping, worker, new MappingProperties());
            scheduler.scan();
            scheduler.work();
            scheduler.recover();
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // Wait for the bounded worker submission before inspecting captured events.
            scheduler.close();
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

    @Test
    void configuredConcurrencyBoundsOutstandingWorkIndependentlyOfScanPage() throws Exception {
        var mapping = mock(MappingService.class);
        var worker = mock(MappingWorker.class);
        var properties = new MappingProperties();
        properties.setConcurrency(2);
        properties.setScanPageSize(101);
        var entered = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var peak = new java.util.concurrent.atomic.AtomicInteger();
        when(worker.runOne())
                .thenAnswer(
                        call -> {
                            int count = active.incrementAndGet();
                            peak.accumulateAndGet(count, Math::max);
                            entered.countDown();
                            try {
                                release.await();
                            } finally {
                                active.decrementAndGet();
                            }
                            return true;
                        });
        var scheduler = new MappingScheduler(mapping, worker, properties);
        try {
            for (int i = 0; i < 10; i++) scheduler.work();
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(peak.get()).isEqualTo(2);
        } finally {
            release.countDown();
            scheduler.close();
        }
        org.mockito.Mockito.verify(worker, org.mockito.Mockito.times(2)).runOne();
    }
}
