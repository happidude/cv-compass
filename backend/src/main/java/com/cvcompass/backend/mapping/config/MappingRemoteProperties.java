package com.cvcompass.backend.mapping.config;

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
@ConfigurationProperties("cv.mapping.remote")
public class MappingRemoteProperties {
    private boolean enabled;
    private Duration leaseDuration = Duration.ofSeconds(120);
    private Duration heartbeatInterval = Duration.ofSeconds(30);
    private Duration hardDeadline = Duration.ofMinutes(15);
    private Duration reclaimGrace = Duration.ofMinutes(2);

    @Min(1)
    @Max(16384)
    private int controlBodyBytes = 16384;

    @Min(1)
    @Max(1048576)
    private int maxResultBytes = 1048576;

    private Map<String, Worker> workers = Map.of();

    @AssertTrue(message = "Invalid remote mapping timing")
    public boolean isTimingValid() {
        return positive(leaseDuration)
                && positive(heartbeatInterval)
                && positive(hardDeadline)
                && positive(reclaimGrace)
                && heartbeatInterval.multipliedBy(2).compareTo(leaseDuration) < 0
                && hardDeadline.compareTo(leaseDuration) > 0;
    }

    @AssertTrue(message = "Invalid remote mapping workers")
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

    private static boolean positive(Duration value) {
        return value != null
                && !value.isNegative()
                && value.toMillis() >= 1
                && value.compareTo(Duration.ofDays(1)) <= 0;
    }

    @Getter
    @Setter
    public static class Worker {
        private Set<String> credentialSha256 = Set.of();
    }
}
