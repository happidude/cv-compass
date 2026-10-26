package com.cvcompass.backend.ingestion.config;

import com.cvcompass.backend.ingestion.enums.OcrProtocol;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("cv.ocr")
public class OcrJobProperties {
    private boolean enabled = false;

    @Min(1)
    @Max(OcrProtocol.MAX_ATTEMPTS)
    private int maxAttempts = 3;

    @NotNull private Duration leaseDuration = Duration.ofSeconds(120);
    @NotNull private Duration heartbeatInterval = Duration.ofSeconds(30);
    @NotNull private Duration attemptTimeout = Duration.ofSeconds(900);
    @NotNull private Duration quarantineGrace = Duration.ofSeconds(60);

    @NotNull
    private List<Duration> retryBackoffs = List.of(Duration.ofSeconds(30), Duration.ofSeconds(120));

    @Min(1)
    @Max(1000)
    private int expiryBatchSize = 50;

    @Min(1)
    @Max(OcrProtocol.MAX_PAGES)
    private int maxPages = OcrProtocol.MAX_PAGES;

    @Min(1)
    @Max(OcrProtocol.MAX_TEXT_CODE_POINTS)
    private int maxTextCodePoints = OcrProtocol.MAX_TEXT_CODE_POINTS;

    @Min(1)
    @Max(OcrProtocol.MAX_DOCUMENT_TEXT)
    private int maxDocumentText = OcrProtocol.MAX_DOCUMENT_TEXT;

    @Min(1)
    @Max(OcrProtocol.MAX_RESULT_BYTES)
    private int maxResultBytes = OcrProtocol.MAX_RESULT_BYTES;

    @Min(1)
    @Max(OcrProtocol.MAX_ENVELOPE_BYTES)
    private int maxEnvelopeBytes = OcrProtocol.MAX_ENVELOPE_BYTES;

    @AssertTrue(message = "OCR durations, heartbeat and retry budget must be consistent")
    public boolean isTimingValid() {
        return positive(leaseDuration)
                && positive(heartbeatInterval)
                && positive(attemptTimeout)
                && positive(quarantineGrace)
                && heartbeatInterval.compareTo(leaseDuration) < 0
                && leaseDuration.compareTo(attemptTimeout) < 0
                && retryBackoffs != null
                && retryBackoffs.size() >= maxAttempts - 1
                && retryBackoffs.size() <= OcrProtocol.MAX_ATTEMPTS - 1
                && retryBackoffs.stream().allMatch(OcrJobProperties::positive)
                && maxResultBytes <= maxEnvelopeBytes;
    }

    private static boolean positive(Duration value) {
        return value != null
                && !value.isNegative()
                && value.compareTo(Duration.ofMillis(1)) >= 0
                && value.compareTo(Duration.ofDays(1)) <= 0;
    }
}
