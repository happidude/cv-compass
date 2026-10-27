package com.cvcompass.backend.ingestion.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    PaginationProperties.class,
    UploadProperties.class,
    BatchProperties.class
})
class CvApiConfiguration {}
