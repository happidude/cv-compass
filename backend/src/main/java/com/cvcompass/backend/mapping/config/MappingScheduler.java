package com.cvcompass.backend.mapping.config;

import com.cvcompass.backend.mapping.service.MappingService;
import com.cvcompass.backend.mapping.service.MappingWorker;
import com.cvcompass.backend.web.OperationalLog;
import java.util.Map;
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

    public MappingScheduler(MappingService mapping, MappingWorker worker) {
        this.mapping = mapping;
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${cv.mapping.scan-delay:30s}")
    public void scan() {
        safely(() -> mapping.scan(100));
    }

    @Scheduled(fixedDelayString = "${cv.mapping.worker-delay:1s}")
    public void work() {
        safely(() -> worker.runOne());
    }

    @Scheduled(fixedDelayString = "${cv.mapping.reaper-delay:15s}")
    public void recover() {
        safely(() -> mapping.reap(100));
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
