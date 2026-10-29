package com.cvcompass.backend.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
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

    private UserDetailsService applicationUsers(JdbcTemplate jdbc) {
        return username ->
                jdbc
                        .query(
                                "SELECT \"USERNAME\",\"PASSWORD_HASH\",\"ROLE\",\"ENABLED\" FROM \"CV_USERS\" WHERE \"USERNAME\"=?",
                                (rs, n) ->
                                        User.withUsername(rs.getString(1))
                                                .password(rs.getString(2))
                                                .roles(rs.getString(3))
                                                .disabled(!rs.getBoolean(4))
                                                .build(),
                                username)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    @Bean
    @Order(2)
    SecurityFilterChain userApiSecurityChain(
            HttpSecurity http, JdbcTemplate jdbc, PasswordEncoder userPasswordEncoder) {
        var provider =
                new org.springframework.security.authentication.dao.DaoAuthenticationProvider(
                        applicationUsers(jdbc));
        provider.setPasswordEncoder(userPasswordEncoder);
        var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        http.securityMatcher("/api/**", "/error")
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .authenticationManager(
                        new org.springframework.security.authentication.ProviderManager(provider))
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

    private static void error(
            jakarta.servlet.http.HttpServletResponse response, int status, String code)
            throws java.io.IOException {
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
