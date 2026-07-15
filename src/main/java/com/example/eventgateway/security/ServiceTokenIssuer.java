package com.example.eventgateway.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Mints short-lived, narrowly-scoped JWTs the Gateway attaches to its own
 * outbound calls to the Account Service. Signed with a secret that only the
 * Gateway and Account Service share - distinct from the secret used to
 * validate client-facing tokens - so this service-to-service trust boundary
 * is independent of whatever identity provider issues client tokens.
 *
 * In a deployment with a real OAuth2 Authorization Server, this would
 * instead be a client_credentials token fetch; minting locally here keeps
 * the sample runnable without standing up an external IdP.
 */
@Component
public class ServiceTokenIssuer {

    private final MACSigner signer;
    private final String issuer;
    private final String audience;
    private final long ttlSeconds;

    public ServiceTokenIssuer(@Value("${security.jwt.internal-secret}") String secret,
                               @Value("${security.jwt.internal-issuer}") String issuer,
                               @Value("${security.jwt.internal-audience}") String audience,
                               @Value("${security.jwt.internal-token-ttl-seconds}") long ttlSeconds) {
        try {
            this.signer = new MACSigner(secret.getBytes(StandardCharsets.UTF_8));
        } catch (JOSEException e) {
            throw new IllegalStateException(
                    "Invalid security.jwt.internal-secret - HS256 requires a secret of at least 32 bytes", e);
        }
        this.issuer = issuer;
        this.audience = audience;
        this.ttlSeconds = ttlSeconds;
    }

    public String issueToken() {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(issuer)
                    .audience(List.of(audience))
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
                    .claim("scope", "internal:accounts")
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign internal service token", e);
        }
    }
}
