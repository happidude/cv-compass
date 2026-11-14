package com.cvcompass.backend.mapping.config;

import com.cvcompass.backend.mapping.service.MappingRemoteService;
import com.cvcompass.backend.web.OperationalLog;
import java.util.Map;
import org.slf4j.event.Level;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cv.mapping.enabled", havingValue = "true")
public class MappingRemoteReaper {
    private final MappingRemoteService service;

    public MappingRemoteReaper(MappingRemoteService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${cv.mapping.reaper-delay:15s}")
    public void recover() {
        try {
            service.reap();
        } catch (RuntimeException failure) {
            OperationalLog.write(
                    Level.ERROR,
                    OperationalLog.Event.MAPPING_SCHEDULER_FAILED,
                    "REMOTE_REAPER_FAILURE",
                    Map.of());
        }
    }
}
