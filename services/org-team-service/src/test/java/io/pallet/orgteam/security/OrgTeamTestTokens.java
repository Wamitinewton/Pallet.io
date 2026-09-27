package io.pallet.orgteam.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

public final class OrgTeamTestTokens {

    private static final KeyPair KEY_PAIR = generateKeyPair();

    private String orgId;
    private String userId;
    private Instant authTime = Instant.now();
    private String sessionId = UUID.randomUUID().toString();
    private List<String> realmRoles = List.of();
    private boolean withSubject = true;
    private final Map<String, String> profileClaims = new LinkedHashMap<>();

    private OrgTeamTestTokens() {}

    public static OrgTeamTestTokens forMember(String orgId, String userId) {
        OrgTeamTestTokens tokens = new OrgTeamTestTokens();
        tokens.orgId = orgId;
        tokens.userId = userId;
        return tokens;
    }

    public static OrgTeamTestTokens forAccount(String userId) {
        return forMember(null, userId);
    }

    public static RSAPublicKey publicKey() {
        return (RSAPublicKey) KEY_PAIR.getPublic();
    }

    public OrgTeamTestTokens claimingRoles(String... roles) {
        this.realmRoles = List.of(roles);
        return this;
    }

    public OrgTeamTestTokens authenticatedAt(Instant authTime) {
        this.authTime = authTime;
        return this;
    }

    public OrgTeamTestTokens withoutAuthTime() {
        this.authTime = null;
        return this;
    }

    public OrgTeamTestTokens withoutSubject() {
        this.withSubject = false;
        return this;
    }

    public OrgTeamTestTokens withEmail(String email) {
        profileClaims.put("email", email);
        return this;
    }

    public OrgTeamTestTokens withName(String name) {
        profileClaims.put("name", name);
        return this;
    }

    public OrgTeamTestTokens withPreferredUsername(String preferredUsername) {
        profileClaims.put("preferred_username", preferredUsername);
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
                .claim("sid", sessionId)
                .claim("realm_access", Map.of("roles", realmRoles));
        if (orgId != null) {
            builder.claim("org_id", orgId);
        }
        profileClaims.forEach(builder::claim);
        if (withSubject) {
            builder.subject(userId);
        }
        if (authTime != null) {
            builder.claim("auth_time", authTime.getEpochSecond());
        }
        return builder.build();
    }

    public String signed() {
        Instant issuedAt = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plusSeconds(300)))
                .claim("sid", sessionId)
                .claim("realm_access", Map.of("roles", realmRoles));
        if (orgId != null) {
            claims.claim("org_id", orgId);
        }
        profileClaims.forEach(claims::claim);
        if (withSubject) {
            claims.subject(userId);
        }
        if (authTime != null) {
            claims.claim("auth_time", authTime.getEpochSecond());
        }
        try {
            SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
            token.sign(new RSASSASigner((RSAPrivateKey) KEY_PAIR.getPrivate()));
            return token.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
