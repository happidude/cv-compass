package com.cvcompass.backend.mapping.config;

import com.cvcompass.backend.mapping.service.MappingService;
import com.cvcompass.backend.mapping.service.MappingWorker;
import com.cvcompass.backend.web.OperationalLog;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.event.Level;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "cv.mapping.enabled", havingValue = "true")
public class MappingScheduler {
    private final MappingService mapping;
    private final MappingWorker worker;
    private final ExecutorService executor;
    private final Semaphore slots;
    private final int concurrency;
    private volatile boolean stopping;

    public MappingScheduler(
            MappingService mapping, MappingWorker worker, MappingProperties properties) {
        this.mapping = mapping;
        this.worker = worker;
        this.concurrency = properties.getConcurrency();
        this.slots = new Semaphore(concurrency);
        this.executor = Executors.newFixedThreadPool(concurrency);
    }

    @Scheduled(fixedDelayString = "${cv.mapping.scan-delay:30s}")
    public void scan() {
        if (!stopping) safely(mapping::scan);
    }

    @Scheduled(fixedDelayString = "${cv.mapping.worker-delay:1s}")
    public synchronized void work() {
        if (stopping) return;
        for (int i = 0; i < concurrency && slots.tryAcquire(); i++) {
            try {
                executor.execute(
                        () -> {
                            try {
                                if (!stopping) safely(worker::runOne);
                            } finally {
                                slots.release();
                            }
                        });
            } catch (RuntimeException rejected) {
                slots.release();
                if (!stopping) throw rejected;
            }
        }
    }

    @Scheduled(fixedDelayString = "${cv.mapping.reaper-delay:15s}")
    public void recover() {
        if (!stopping) safely(mapping::reap);
    }

    @PreDestroy
    public void close() {
        synchronized (this) {
            stopping = true;
            executor.shutdown();
        }
        executor.close();
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            OperationalLog.write(
                    Level.ERROR,
                    OperationalLog.Event.MAPPING_SCHEDULER_FAILED,
                    "SCHEDULER_FAILURE",
                    Map.of("exceptionType", failure.getClass().getSimpleName()));
        }
    }
}
