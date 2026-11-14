package com.cvcompass.backend.mapping.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** A separate chain prevents OCR or user credentials from authorizing mapping workers. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
@EnableConfigurationProperties(MappingRemoteProperties.class)
public class MappingRemoteSecurityConfiguration implements WebMvcConfigurer {
    @Bean
    MappingWorkerAuthenticationProvider mappingAuthenticationProvider(
            MappingRemoteProperties properties) {
        return new MappingWorkerAuthenticationProvider(properties);
    }

    @Bean
    @Order(0)
    SecurityFilterChain mappingRemoteSecurity(
            HttpSecurity http,
            MappingRemoteProperties properties,
            MappingWorkerAuthenticationProvider provider) {
        http.securityMatcher("/internal/mapping/**")
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                // Applies only to this bearer-only internal chain, not a global CSRF disable.
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authenticationProvider(provider)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("MAPPING_WORKER"))
                .addFilterBefore(
                        new MappingWorkerAuthenticationFilter(properties, provider),
                        AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
