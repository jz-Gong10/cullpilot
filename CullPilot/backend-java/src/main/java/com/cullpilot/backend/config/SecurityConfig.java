package com.cullpilot.backend.config;

import com.cullpilot.backend.security.ApiAccessDeniedHandler;
import com.cullpilot.backend.security.ApiAuthenticationEntryPoint;
import com.cullpilot.backend.security.BearerTokenAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public FilterRegistrationBean<BearerTokenAuthenticationFilter> bearerFilterRegistration(
            BearerTokenAuthenticationFilter filter) {
        FilterRegistrationBean<BearerTokenAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            BearerTokenAuthenticationFilter bearerTokenFilter,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/health",
                                "/actuator/health",
                                "/error")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .addFilterBefore(bearerTokenFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
