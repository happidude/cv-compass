package com.cvcompass.backend.catalog.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class NormalizationSecurityConfiguration {
    @Bean
    NormalizationWorkerAuthenticationProvider normalizationAuthenticationProvider(
            NormalizationProperties properties) {
        return new NormalizationWorkerAuthenticationProvider(properties);
    }

    @Bean
    @Order(0)
    SecurityFilterChain normalizationSecurity(
            HttpSecurity http,
            NormalizationProperties properties,
            NormalizationWorkerAuthenticationProvider provider) {
        http.securityMatcher("/internal/normalization/**")
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                // This bearer-only chain has no browser session credentials.
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authenticationProvider(provider)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("NORMALIZATION_WORKER"))
                .addFilterBefore(
                        new NormalizationWorkerAuthenticationFilter(properties, provider),
                        AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
