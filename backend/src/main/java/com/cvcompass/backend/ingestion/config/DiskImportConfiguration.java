package com.cvcompass.backend.ingestion.config;

import com.cvcompass.backend.ingestion.source.ImportSource;
import com.cvcompass.backend.ingestion.source.LocalDiskImportSource;
import java.io.IOException;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "cv.import", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({DiskImportProperties.class, BatchProperties.class})
@EnableScheduling
public class DiskImportConfiguration {
    public DiskImportConfiguration(Environment environment) {
        if (environment.containsProperty("cv.import.batch-size")) {
            throw new IllegalStateException(
                    "Use cv.batch.max-files; cv.import.batch-size is retired");
        }
    }

    @Bean
    public Clock diskImportClock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(
            prefix = "cv.import",
            name = "provider",
            havingValue = "local",
            matchIfMissing = true)
    public ImportSource importSource(
            DiskImportProperties properties,
            @Value("${cv.storage.local.root-directory}") String storage)
            throws IOException {
        return new LocalDiskImportSource(
                properties.getSourceDirectory(),
                properties.getProcessedDirectory(),
                properties.getFailedDirectory(),
                storage);
    }
}
