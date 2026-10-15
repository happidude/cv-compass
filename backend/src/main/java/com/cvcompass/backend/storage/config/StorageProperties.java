package com.cvcompass.backend.storage.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("cv.storage")
@Validated
public record StorageProperties(@NotBlank String provider) {
    public StorageProperties {
        if (!"local".equals(provider)) {
            throw new IllegalArgumentException("cv.storage.provider must explicitly select a supported provider: local");
        }
    }
}
