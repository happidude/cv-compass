package com.cvcompass.backend.ingestion.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("cv.pagination")
public class PaginationProperties {
    @Min(1)
    private int defaultPageSize = 20;

    // Bound the look-ahead allocation; deployments may choose a smaller response.
    @Min(1)
    @Max(1000)
    private int maxPageSize = 100;

    @AssertTrue(message = "Default page size must not exceed maximum page size")
    public boolean isPageSizeValid() {
        return defaultPageSize <= maxPageSize;
    }
}
