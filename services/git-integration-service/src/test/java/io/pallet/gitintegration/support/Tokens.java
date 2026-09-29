package io.pallet.gitintegration.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Keycloak-shaped access tokens signed with a key generated per test JVM. */
public final class Tokens {

    private static final KeyPair KEY_PAIR = TestSecrets.rsaKeyPair(2048);

    private final String userId;
    private final String sessionId = UUID.randomUUID().toString();
    private final Map<String, Object> extraClaims = new LinkedHashMap<>();
    private boolean withSubject = true;

    private Tokens(String userId) {
        this.userId = userId;
    }

    public static Tokens forUser(String userId) {
        return new Tokens(userId);
    }

    public Tokens claimingOrg(String orgId) {
        extraClaims.put("org_id", orgId);
        return this;
    }

    public Tokens claimingRoles(String... roles) {
        extraClaims.put("realm_access", Map.of("roles", List.of(roles)));
        return this;
    }

    public Tokens withoutSubject() {
        withSubject = false;
        return this;
    }

    public String sessionId() {
        return sessionId;
    }

    public Jwt jwt() {
        Instant issuedAt = Instant.now();
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(300))
                .claim("sid", sessionId);
        extraClaims.forEach(builder::claim);
        if (withSubject) {
            builder.subject(userId);
        }
        return builder.build();
    }

    public String signed() {
        Instant issuedAt = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plusSeconds(300)))
                .claim("sid", sessionId);
        extraClaims.forEach(claims::claim);
        if (withSubject) {
            claims.subject(userId);
        }
        try {
            SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
            token.sign(new RSASSASigner(KEY_PAIR.getPrivate()));
            return token.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Verifies {@link #signed()} tokens with every registered validator, the session revocation check included. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class LocalDecoder {

        @Bean
        JwtDecoder jwtDecoder(List<OAuth2TokenValidator<Jwt>> validators) {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY_PAIR.getPublic())
                    .build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefault(), new DelegatingOAuth2TokenValidator<>(validators)));
            return decoder;
        }
    }
}
