package com.cvcompass.backend.identity.config;

import com.cvcompass.backend.identity.service.UserLookupService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class UserSecurity {
    @Bean
    PasswordEncoder userPasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    @Order(2)
    SecurityFilterChain userApiSecurityChain(
            HttpSecurity http, UserLookupService users, PasswordEncoder userPasswordEncoder) {
        var provider = new DaoAuthenticationProvider(users::load);
        provider.setPasswordEncoder(userPasswordEncoder);
        var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        http.securityMatcher("/api/**", "/error")
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .authenticationManager(new ProviderManager(provider))
                .csrf(
                        config ->
                                config.csrfTokenRepository(csrf)
                                        .csrfTokenRequestHandler(
                                                new CsrfTokenRequestAttributeHandler()))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers("/api/auth/csrf", "/api/auth/login")
                                        .permitAll()
                                        .anyRequest()
                                        .hasAnyRole("USER", "ADMIN"))
                .requestCache(cache -> cache.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(
                        login ->
                                login.loginProcessingUrl("/api/auth/login")
                                        .successHandler(
                                                (request, response, authentication) -> {
                                                    response.setStatus(204);
                                                    response.setHeader("Cache-Control", "no-store");
                                                })
                                        .failureHandler(
                                                (request, response, exception) ->
                                                        error(response, 401, "LOGIN_FAILED")))
                .logout(
                        logout ->
                                logout.logoutUrl("/api/auth/logout")
                                        .deleteCookies("JSESSIONID", "XSRF-TOKEN")
                                        .logoutSuccessHandler(
                                                (request, response, auth) ->
                                                        response.setStatus(204)))
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (request, response, ex) ->
                                                        error(response, 401, "AUTH_REQUIRED"))
                                        .accessDeniedHandler(
                                                (request, response, ex) ->
                                                        error(response, 403, "ACCESS_DENIED")));
        return http.build();
    }

    private static void error(HttpServletResponse response, int status, String code)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter()
                .write(
                        "{\"code\":\""
                                + code
                                + "\",\"message\":\"Request denied\",\"correlationId\":null,\"details\":[]}");
    }
}
