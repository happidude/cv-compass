package com.cvcompass.backend.catalog.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CatalogImportProperties.class, CatalogTaxonomyProperties.class})
public class CatalogConfiguration {}
