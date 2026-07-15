package com.example.eventgateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
public class JwtDecoderConfig {

    /**
     * Validates client-facing bearer tokens (HS256, shared secret). Swap
     * this for NimbusJwtDecoder.withJwkSetUri(...) if/when a real OAuth2
     * Authorization Server issues these tokens instead of a pre-shared key.
     */
    @Bean
    public JwtDecoder clientJwtDecoder(@Value("${security.jwt.client-secret}") String secret) {
        SecretKeySpec key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).build();
    }

    /**
     * Bearer tokens only - no query-param token support, which would leak
     * tokens into access logs.
     */
    @Bean
    public DefaultBearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver resolver = new DefaultBearerTokenResolver();
        resolver.setAllowUriQueryParameter(false);
        return resolver;
    }
}
