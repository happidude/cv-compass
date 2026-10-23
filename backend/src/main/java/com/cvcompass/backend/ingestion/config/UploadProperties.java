package com.cvcompass.backend.ingestion.config;

import com.cvcompass.backend.ingestion.upload.PdfUploadValidation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("cv.upload")
public class UploadProperties {
    // M4 protocol ceiling; operational limits may be reduced through configuration.
    @Min(1)
    @Max(PdfUploadValidation.MAX_BYTES)
    private long maxFileBytes = PdfUploadValidation.MAX_BYTES;
}
