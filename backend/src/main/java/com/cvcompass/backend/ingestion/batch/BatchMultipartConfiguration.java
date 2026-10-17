package com.cvcompass.backend.ingestion.batch;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

/** A finite parser ceiling; individual file limits are enforced by streaming validation. */
@Configuration(proxyBeanMethods = false)
public class BatchMultipartConfiguration {
    @Bean
    MultipartConfigElement multipartConfigElement(
            @Value("${cv.batch.max-files:10}") int files,
            @Value("${cv.batch.max-file-bytes:10485760}") long bytes) {
        if (files < 1 || files > 10 || bytes < 1 || bytes > 10485760) {
            throw new IllegalArgumentException("Invalid batch limits");
        }
        long request = Math.max(10485760L, files * bytes) + 1048576L;
        return new MultipartConfigElement("", request, request, 0);
    }
}
