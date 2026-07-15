package com.example.eventgateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Client-facing authentication for the Event Gateway. External callers must
 * present a valid JWT; submitting events additionally requires the
 * "events:write" scope. Health checks stay open for the orchestrator/LB.
 *
 * This is separate from the internal Gateway -> Account Service auth (see
 * {@link com.example.eventgateway.security.ServiceTokenIssuer}), which uses
 * its own secret so a client-facing token can never be replayed against the
 * internal service.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String EVENTS_WRITE_SCOPE = "SCOPE_events:write";

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder clientJwtDecoder) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/events").hasAuthority(EVENTS_WRITE_SCOPE)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(clientJwtDecoder)));
        return http.build();
    }
}
