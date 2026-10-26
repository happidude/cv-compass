package com.cvcompass.backend.ingestion.service;

import com.cvcompass.backend.ingestion.config.OcrAdmissionProperties;
import com.cvcompass.backend.ingestion.config.OcrIntegrationProperties;
import com.cvcompass.backend.ingestion.dto.OcrAdmission;
import com.cvcompass.backend.ingestion.entity.CvDocument;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Selects an enrolled deployment after shared PDF validation/storage, without reading files. */
@Service
public class OcrAdmissionPolicy {
    private final OcrAdmissionProperties properties;
    private final OcrIntegrationProperties integration;

    public OcrAdmissionPolicy(
            OcrAdmissionProperties properties, OcrIntegrationProperties integration) {
        this.properties = properties;
        this.integration = integration;
    }

    public Optional<OcrAdmission> admit(CvDocument document) {
        if (!integration.isEnabled()) return Optional.empty();
        if (properties.getMode() == OcrAdmissionProperties.Mode.LOCAL) {
            return Optional.of(
                    new OcrAdmission("local", properties.getLocalProfileRevision(), null));
        }
        return integration.getFixtures().stream()
                .filter(
                        fixture ->
                                fixture.getSha256().equals(document.getSha256())
                                        && fixture.getSizeBytes() == document.getFileSize())
                .findFirst()
                .map(
                        fixture ->
                                new OcrAdmission(
                                        "synthetic-only",
                                        fixture.getProfileRevision(),
                                        fixture.getFixtureId()));
    }
}
