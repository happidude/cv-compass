package com.cvcompass.backend.mapping.config;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("cv.mapping")
public class MappingProperties {
    @Min(1)
    @Max(OcrExtractionReader.MAX_QUERY_PAGE_SIZE)
    private int scanPageSize = 100;

    @Min(1)
    @Max(OcrExtractionReader.MAX_QUERY_PAGE_SIZE)
    private int reaperPageSize = 100;

    @Min(1)
    @Max(16)
    private int concurrency = 1;

    @Min(1)
    @Max(1000)
    private int maxAttempts = 3;

    @NotNull private Duration scanDelay = Duration.ofSeconds(30);
    @NotNull private Duration workerDelay = Duration.ofSeconds(1);
    @NotNull private Duration reaperDelay = Duration.ofSeconds(15);
    @NotNull private Duration leaseDuration = Duration.ofSeconds(120);
    @NotNull private Duration hardDeadline = Duration.ofSeconds(120);
    @NotNull private Duration retryBackoff = Duration.ofSeconds(30);

    @AssertTrue(
            message = "Mapping durations must be 1ms..1day and lease must not exceed hard deadline")
    public boolean isTimingValid() {
        return positive(scanDelay)
                && positive(workerDelay)
                && positive(reaperDelay)
                && positive(leaseDuration)
                && positive(hardDeadline)
                && positive(retryBackoff)
                && leaseDuration.compareTo(hardDeadline) <= 0;
    }

    private static boolean positive(Duration value) {
        return value != null
                && !value.isNegative()
                && value.toMillis() >= 1
                && value.compareTo(Duration.ofDays(1)) <= 0;
    }
}
