package com.cvcompass.backend.ingestion.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Only internal OCR routes match. Unmatched public routes retain their pre-M7 policy. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
@EnableConfigurationProperties(OcrIntegrationProperties.class)
public class OcrSecurityConfiguration implements WebMvcConfigurer {
    @Bean
    OcrWorkerAuthenticationProvider ocrAuthenticationProvider(OcrIntegrationProperties properties) {
        return new OcrWorkerAuthenticationProvider(properties);
    }

    @Bean
    @Order(1)
    SecurityFilterChain ocrSecurity(
            HttpSecurity http,
            OcrIntegrationProperties properties,
            OcrWorkerAuthenticationProvider provider) {
        http.securityMatcher("/internal/ocr/**")
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                // Applies only to this bearer-only internal chain, not a global CSRF disable.
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authenticationProvider(provider)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("OCR_WORKER"))
                .addFilterBefore(
                        new OcrWorkerAuthenticationFilter(properties, provider),
                        AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
