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
@ConfigurationProperties("cv.batch")
public class BatchProperties {
    public static final int MAX_FILES = 1000;

    @Min(1)
    @Max(MAX_FILES)
    private int maxFiles = 10;

    @Min(1)
    @Max(PdfUploadValidation.MAX_BYTES)
    private long maxFileBytes = PdfUploadValidation.MAX_BYTES;

    @Min(1)
    private long multipartOverheadBytes = 1048576;
}
