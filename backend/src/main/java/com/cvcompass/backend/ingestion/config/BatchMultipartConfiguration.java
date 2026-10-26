package com.cvcompass.backend.ingestion.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** A finite parser ceiling; individual file limits are enforced by streaming validation. */
@Configuration(proxyBeanMethods = false)
class BatchMultipartConfiguration {
    @Bean
    MultipartConfigElement multipartConfigElement(BatchProperties batch, UploadProperties upload) {
        long batchBytes = Math.multiplyExact(batch.getMaxFiles(), batch.getMaxFileBytes());
        long request =
                Math.addExact(
                        Math.max(upload.getMaxFileBytes(), batchBytes),
                        batch.getMultipartOverheadBytes());
        return new MultipartConfigElement("", request, request, 0);
    }
}
