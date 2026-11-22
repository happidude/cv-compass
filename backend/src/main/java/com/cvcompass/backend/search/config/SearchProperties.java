package com.cvcompass.backend.search.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("cv.search")
public class SearchProperties {
    @Min(1)
    @Max(1000)
    private int rebuildBatchSize = 200;

    @NotBlank private String nightlyCron = "0 30 2 * * *";
}
