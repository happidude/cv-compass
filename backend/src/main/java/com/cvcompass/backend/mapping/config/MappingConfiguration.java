package com.cvcompass.backend.mapping.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    MappingProperties.class,
    MappingRemoteProperties.class,
    RemapProperties.class
})
public class MappingConfiguration {}
