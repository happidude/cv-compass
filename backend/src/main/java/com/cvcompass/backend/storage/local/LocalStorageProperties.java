package com.cvcompass.backend.storage.local;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("cv.storage.local")
@Validated
public record LocalStorageProperties(@NotBlank String rootDirectory) {
}
