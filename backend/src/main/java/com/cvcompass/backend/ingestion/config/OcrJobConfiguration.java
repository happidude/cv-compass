package com.cvcompass.backend.ingestion.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OcrJobProperties.class)
public class OcrJobConfiguration {
    public OcrJobConfiguration(OcrJobProperties properties, Environment environment) {
        if (properties.isEnabled() && environment.matchesProfiles("prod")) {
            throw new IllegalStateException("OCR production blocked: GCC/Tesseract unresolved");
        }
    }
}
