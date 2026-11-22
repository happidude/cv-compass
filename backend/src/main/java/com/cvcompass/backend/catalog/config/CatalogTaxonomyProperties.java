package com.cvcompass.backend.catalog.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("cv.catalog.taxonomy")
public class CatalogTaxonomyProperties {
    @NotBlank private String resourcePath = "classpath:catalog/taxonomy-v0.json";

    @NotBlank
    @Size(max = 32)
    private String versionLabel = "cv-taxonomy/0";
}
