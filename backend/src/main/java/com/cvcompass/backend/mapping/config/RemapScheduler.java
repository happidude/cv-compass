package com.cvcompass.backend.mapping.config;

import com.cvcompass.backend.mapping.service.RemapService;
import com.cvcompass.backend.web.OperationalLog;
import java.util.Map;
import org.slf4j.event.Level;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cv.mapping.enabled", havingValue = "true")
public class RemapScheduler {
    private final RemapService service;
    private final RemapProperties properties;

    public RemapScheduler(RemapService service, RemapProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${cv.mapping.remap.delay:30s}")
    public void tick() {
        if (!properties.isEnabled()) return;
        try {
            for (long id : service.running()) {
                try {
                    service.advance(id);
                } catch (RuntimeException failure) {
                    failed(failure);
                }
            }
        } catch (RuntimeException failure) {
            failed(failure);
        }
    }

    private static void failed(RuntimeException failure) {
        OperationalLog.write(
                Level.ERROR,
                OperationalLog.Event.MAPPING_SCHEDULER_FAILED,
                "REMAP_BATCH_FAILED",
                Map.of("exceptionType", failure.getClass().getSimpleName()));
    }
}
