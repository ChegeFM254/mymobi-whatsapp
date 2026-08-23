package com.mfstechnologies.mymobi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * IMPORTANT: this class exists for three reasons, and the second one
 * is the critical one.
 *
 * 1. Provides a BCryptPasswordEncoder bean for hashing PINs - equivalent
 *    to bcryptjs in the Node.js version (never store or compare a PIN as
 *    plain text).
 *
 * 2. Explicitly disables Spring Security's DEFAULT behavior. Simply
 *    having spring-boot-starter-security on the classpath (which it now
 *    is, purely for the password encoder above) makes Spring Security
 *    auto-configure HTTP Basic Auth on EVERY endpoint by default,
 *    including /webhook. Without this class, Meta would never be able
 *    to call the webhook at all - every request would get rejected with
 *    401 Unauthorized before it even reached WebhookController. This
 *    app does its own authentication (the WhatsApp UPN/PIN/Verification
 *    Code flow) - Spring Security's own auth mechanisms are explicitly
 *    turned off here, and only the password encoder is used.
 *
 * 3. Provides an empty UserDetailsService bean. Without this, Spring
 *    Boot's UserDetailsServiceAutoConfiguration still generates a random
 *    "dev" user/password on every startup (logged at WARN) purely
 *    because no UserDetailsService/AuthenticationManager/
 *    AuthenticationProvider bean exists - regardless of what the filter
 *    chain's authorization rules say. Harmless in practice (nothing
 *    ever needs to authenticate against it, since permitAll() covers
 *    every request), but noisy. An empty user store accurately reflects
 *    that this app genuinely never uses Spring Security's own
 *    authentication mechanism at all.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public org.springframework.security.core.userdetails.UserDetailsService userDetailsService() {
        return new org.springframework.security.provisioning.InMemoryUserDetailsManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
