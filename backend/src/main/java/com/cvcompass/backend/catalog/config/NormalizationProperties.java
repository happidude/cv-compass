package com.cvcompass.backend.catalog.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("cv.catalog.normalization")
public class NormalizationProperties {
    private boolean enabled;
    private Duration leaseDuration = Duration.ofMinutes(2);
    private Duration retryBackoff = Duration.ofSeconds(30);

    @Min(1)
    @Max(1000)
    private int batchSize = 100;

    @Min(1)
    @Max(1048576)
    private int maxBodyBytes = 65536;

    private Map<String, Worker> workers = Map.of();

    @AssertTrue(message = "Invalid normalization timing")
    public boolean isTimingValid() {
        return positive(leaseDuration) && positive(retryBackoff);
    }

    @AssertTrue(message = "Invalid normalization workers")
    public boolean isWorkersValid() {
        if (workers == null) return false;
        var seen = new HashSet<String>();
        for (var entry : workers.entrySet()) {
            if (!entry.getKey().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                    || entry.getValue() == null
                    || entry.getValue().credentialSha256 == null) return false;
            for (String hash : entry.getValue().credentialSha256)
                if (hash == null || !hash.matches("[0-9a-f]{64}") || !seen.add(hash)) return false;
        }
        return true;
    }

    private static boolean positive(Duration duration) {
        return duration != null
                && duration.toMillis() >= 1
                && duration.compareTo(Duration.ofDays(1)) <= 0;
    }

    @Getter
    @Setter
    public static class Worker {
        private Set<String> credentialSha256 = Set.of();
    }
}
