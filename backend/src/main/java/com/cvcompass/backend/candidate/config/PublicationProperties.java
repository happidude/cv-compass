package com.cvcompass.backend.candidate.config;

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
@ConfigurationProperties("cv.candidate.publication")
public class PublicationProperties {
    @Min(1)
    @Max(100)
    private int listPageSize = 20;

    private boolean enabled = false;

    @Min(1)
    @Max(1000)
    private int pageSize = 50;

    @Min(1)
    @Max(4)
    private int concurrency = 1;

    @NotNull private Duration delay = Duration.ofSeconds(10);

    @AssertTrue(message = "Publication delay must be 1s..1day")
    public boolean isDelayValid() {
        return delay != null
                && delay.compareTo(Duration.ofSeconds(1)) >= 0
                && delay.compareTo(Duration.ofDays(1)) <= 0;
    }
}
