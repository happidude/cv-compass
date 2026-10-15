package com.cvcompass.backend.storage.config;

import com.cvcompass.backend.storage.FileStorageService;
import com.cvcompass.backend.storage.local.LocalFileStorageService;
import com.cvcompass.backend.storage.local.LocalStorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "cv.storage", name = "provider", havingValue = "local")
    @EnableConfigurationProperties(LocalStorageProperties.class)
    static class LocalConfiguration {
        @Bean
        FileStorageService fileStorageService(LocalStorageProperties properties) {
            return new LocalFileStorageService(properties.rootDirectory());
        }
    }
}
