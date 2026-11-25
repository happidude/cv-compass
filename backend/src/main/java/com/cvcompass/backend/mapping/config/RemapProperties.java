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
@ConfigurationProperties("cv.mapping.remap")
public class RemapProperties {
    private boolean enabled = true;
    @NotNull private Duration delay = Duration.ofSeconds(30);

    @Min(1)
    @Max(OcrExtractionReader.MAX_QUERY_PAGE_SIZE)
    private int batchSize = 100;

    @Min(1)
    @Max(100000)
    private int maxPending = 500;

    @Min(1)
    @Max(32767)
    private int priority = 10;

    @Min(1)
    @Max(1000)
    private int listLimit = 20;

    @AssertTrue(message = "Remap delay must be 1ms..1day")
    public boolean isTimingValid() {
        return delay != null
                && !delay.isNegative()
                && delay.toMillis() >= 1
                && delay.compareTo(Duration.ofDays(1)) <= 0;
    }
}
