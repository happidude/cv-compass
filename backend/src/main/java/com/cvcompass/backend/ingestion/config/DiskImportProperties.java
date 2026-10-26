package com.cvcompass.backend.ingestion.config;

import jakarta.validation.constraints.*;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties("cv.import")
public class DiskImportProperties {
    @Pattern(regexp = "local")
    @NotBlank
    private String provider = "local";

    @NotBlank private String sourceDirectory;
    private String processedDirectory;
    private String failedDirectory;

    @Min(1)
    @Max(10)
    private int batchSize = 10;

    @Min(1)
    @Max(100)
    private int maxAttempts = 3;

    @NotNull private Duration scanInterval = Duration.ofSeconds(30);
    @NotNull private Duration stabilityInterval = Duration.ofSeconds(10);
    @NotNull private Duration retryBackoff = Duration.ofSeconds(30);

    @AssertTrue(message = "Import durations must be positive and at most one day")
    public boolean isDurationConfigurationValid() {
        return positive(scanInterval) && positive(stabilityInterval) && positive(retryBackoff);
    }

    private boolean positive(Duration value) {
        return value != null
                && !value.isNegative()
                && value.toMillis() >= 1
                && value.compareTo(Duration.ofDays(1)) <= 0;
    }
}
