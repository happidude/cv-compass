package com.cvcompass.backend.ingestion.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.*;

@Service
@ConditionalOnProperty(prefix="cv.ocr.integration",name="enabled",havingValue="true")
public class OcrLeaseReaper {
    private static final Logger log=LoggerFactory.getLogger(OcrLeaseReaper.class);
    private final OcrJobService jobs;
    public OcrLeaseReaper(OcrJobService jobs) { this.jobs=jobs; }
    @Scheduled(fixedDelayString="${cv.ocr.integration.reaper-delay:15s}")
    public void expire() {
        try { jobs.expireDue(); }
        catch (RuntimeException failure) {
            com.cvcompass.backend.web.OperationalLog.write(org.slf4j.event.Level.ERROR,
                    com.cvcompass.backend.web.OperationalLog.Event.OCR_REAPER_FAILED,
                    "REAPER_FAILED", java.util.Map.of("exceptionType", failure.getClass().getName()));
        }
    }
}
