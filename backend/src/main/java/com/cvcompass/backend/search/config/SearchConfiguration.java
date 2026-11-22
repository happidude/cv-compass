package com.cvcompass.backend.search.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SearchConfiguration {
    @Bean
    public SearchClock searchClock() {
        return new SearchClock(Clock.systemDefaultZone());
    }
}
