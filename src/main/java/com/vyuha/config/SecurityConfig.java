package com.vyuha.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyuha.common.ApiResponse;
import com.vyuha.common.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/css/**", "/js/**", "/images/**", "/error", "/access-denied").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/field/**").hasAnyRole("FIELD_STAFF", "ADMIN", "ORGANIZER")
                        .requestMatchers("/api/field/**").hasAnyRole("FIELD_STAFF", "ADMIN", "ORGANIZER")
                        .requestMatchers("/events/**").hasAnyRole("ADMIN", "ORGANIZER", "SAFETY_REVIEWER")
                        .requestMatchers("/api/events/**").hasAnyRole("ADMIN", "ORGANIZER", "SAFETY_REVIEWER")
                        .requestMatchers("/guidelines/**").authenticated()
                        .requestMatchers("/api/guidelines/**").authenticated()
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .usernameParameter("email")
                        .passwordParameter("password")
                        .successHandler(roleBasedSuccessHandler())
                        .failureUrl("/login?error")
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll()
                )
                .exceptionHandling(ex -> ex
                        .accessDeniedHandler(accessDeniedHandler())
                );

        return http.build();
    }

    @Bean
    public AuthenticationSuccessHandler roleBasedSuccessHandler() {
        return (request, response, authentication) -> {
            boolean isFieldStaff = authentication.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_FIELD_STAFF".equals(a.getAuthority()));
            String targetUrl = isFieldStaff ? "/field" : "/events";
            response.sendRedirect(request.getContextPath() + targetUrl);
        };
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            if (GlobalExceptionHandler.isApiRequest(request)) {
                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                objectMapper.writeValue(
                        response.getWriter(),
                        ApiResponse.error("FORBIDDEN", "You do not have permission to access this resource.")
                );
                return;
            }
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            request.getRequestDispatcher("/access-denied").forward(request, response);
        };
    }
}
