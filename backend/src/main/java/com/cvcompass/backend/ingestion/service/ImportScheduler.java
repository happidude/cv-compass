package com.cvcompass.backend.ingestion.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "cv.import", name = "enabled", havingValue = "true")
public class ImportScheduler {
    private final DiskImportService imports;
    public ImportScheduler(DiskImportService imports) { this.imports=imports; }
    @Scheduled(fixedDelayString = "${cv.import.scan-interval:30s}", initialDelayString = "${cv.import.scan-interval:30s}")
    public void scan() { imports.tick(); }
}
