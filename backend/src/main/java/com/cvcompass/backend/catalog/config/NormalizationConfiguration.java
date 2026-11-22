package com.cvcompass.backend.catalog.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NormalizationProperties.class)
public class NormalizationConfiguration {
    @Bean
    NormalizationClock normalizationClock() {
        return new NormalizationClock(Clock.systemUTC());
    }
}
