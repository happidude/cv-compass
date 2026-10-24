package com.cvcompass.backend.ingestion.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Operator opt-in only; neither HTTP callers nor document metadata select a policy. */
@Getter
@Setter
@Validated
@ConfigurationProperties("cv.ocr.integration.admission")
public class OcrAdmissionProperties {
    public enum Mode { SYNTHETIC_ONLY, LOCAL }

    @NotNull
    private Mode mode = Mode.SYNTHETIC_ONLY;
    private String localProfileRevision;

    @AssertTrue(message = "LOCAL admission requires local-profile-revision; SYNTHETIC_ONLY must not set it")
    public boolean isSelectionValid() {
        return mode == Mode.LOCAL
                ? localProfileRevision != null && localProfileRevision.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
                : localProfileRevision == null || localProfileRevision.isBlank();
    }
}
