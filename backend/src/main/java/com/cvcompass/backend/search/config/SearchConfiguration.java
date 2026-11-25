package com.cvcompass.backend.search.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchProperties.class)
@EnableScheduling
public class SearchConfiguration {
    @Bean
    static BeanPostProcessor searchSavepoints() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                // Index writes use JDBC only; JPA entities must never be mutated in the savepoint.
                if (bean instanceof JpaTransactionManager manager)
                    manager.setNestedTransactionAllowed(true);
                return bean;
            }
        };
    }
}
