package com.cvcompass.backend.ingestion.config;

import com.cvcompass.backend.ingestion.source.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.io.IOException;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "cv.import", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DiskImportProperties.class)
@EnableScheduling
public class DiskImportConfiguration {
    @Bean public Clock diskImportClock() { return Clock.systemUTC(); }
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "cv.import", name = "provider", havingValue = "local", matchIfMissing = true)
    public ImportSource importSource(DiskImportProperties properties,
            @Value("${cv.storage.local.root-directory}") String storage,
            @Value("${cv.batch.max-files:10}") int maxFiles) throws IOException {
        if (properties.getBatchSize() > maxFiles) throw new IllegalArgumentException("Import batch size exceeds configured batch limit");
        return new LocalDiskImportSource(properties.getSourceDirectory(), properties.getProcessedDirectory(),
                properties.getFailedDirectory(), storage);
    }
}
