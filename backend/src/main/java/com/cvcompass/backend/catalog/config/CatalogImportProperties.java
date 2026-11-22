package com.cvcompass.backend.catalog.config;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("cv.catalog.import")
public class CatalogImportProperties {
    @Min(1)
    private int maxEntries = 10000;

    @Min(1)
    private int maxAliasesPerEntry = 50;
}
