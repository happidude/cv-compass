package com.cvcompass.backend.ingestion.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.nio.file.Path;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(OcrIntegrationProperties.class)
@EnableScheduling
public class OcrIntegrationConfiguration {
    public OcrIntegrationConfiguration(OcrIntegrationProperties properties, OcrJobProperties jobs, Environment environment) {
        if (properties.isEnabled()) {
            if (!jobs.isEnabled() || environment.matchesProfiles("prod"))
                throw new IllegalStateException("OCR integration unavailable; production GCC/Tesseract gate remains closed");
            String root = environment.getProperty("cv.storage.local.root-directory");
            if (!"local".equals(environment.getProperty("cv.storage.provider")) || root == null
                    || !Path.of(root).toAbsolutePath().normalize().equals(properties.getSyntheticStorageRoot().normalize()))
                throw new IllegalStateException("OCR requires dedicated synthetic storage namespace");
        }
    }
}
