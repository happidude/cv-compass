package com.cvcompass.backend.ingestion.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.nio.file.Path;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties({OcrIntegrationProperties.class, OcrAdmissionProperties.class})
@EnableScheduling
public class OcrIntegrationConfiguration {
    public OcrIntegrationConfiguration(OcrIntegrationProperties properties, OcrJobProperties jobs,
            OcrAdmissionProperties admission, Environment environment) {
        if (properties.isEnabled()) {
            if (!jobs.isEnabled() || environment.matchesProfiles("prod"))
                throw new IllegalStateException("OCR integration unavailable; production GCC/Tesseract gate remains closed");
            String root = environment.getProperty("cv.storage.local.root-directory");
            boolean synthetic = admission.getMode() == OcrAdmissionProperties.Mode.SYNTHETIC_ONLY;
            if (!"local".equals(environment.getProperty("cv.storage.provider")) || root == null)
                throw new IllegalStateException("OCR integration requires configured local storage");
            if (synthetic && (properties.getSyntheticStorageRoot() == null
                    || !Path.of(root).toAbsolutePath().normalize().equals(properties.getSyntheticStorageRoot().normalize())))
                throw new IllegalStateException("SYNTHETIC_ONLY admission requires matching synthetic storage root");
            if (synthetic && properties.getFixtures().isEmpty())
                throw new IllegalStateException("SYNTHETIC_ONLY admission requires registered fixtures");
            if (!synthetic && (!properties.getProfiles().containsKey(admission.getLocalProfileRevision())
                    || properties.getWorkers().values().stream().noneMatch(worker -> worker.getProfiles()
                    .contains(admission.getLocalProfileRevision()))))
                throw new IllegalStateException("LOCAL admission requires a registered profile authorized for a worker");
        }
    }
}
