package io.pallet.orgteam.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SignedActionToken {

    private static final String PURPOSE_CLAIM = "purpose";

    private SignedActionToken() {}

    public static String issue(
            String purpose, Map<String, String> claims, Instant issuedAt, Instant expiresAt, String hmacSecret) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
        claims.forEach(builder::claim);
        JWTClaimsSet claimSet = builder.claim(PURPOSE_CLAIM, purpose)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimSet);
        try {
            jwt.sign(new MACSigner(hmacSecret.getBytes(StandardCharsets.UTF_8)));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign action token", e);
        }
        return jwt.serialize();
    }

    public static Map<String, String> verify(String purpose, String token, String hmacSecret, Clock clock) {
        SignedJWT jwt = parse(token);
        verifySignature(jwt, hmacSecret);
        JWTClaimsSet claims = claimsOf(jwt);
        verifyTimeWindow(claims, clock.instant());
        if (!purpose.equals(claims.getClaim(PURPOSE_CLAIM))) {
            throw new InvalidTokenException("Token purpose mismatch");
        }
        return toClaimMap(claims);
    }

    private static SignedJWT parse(String token) {
        try {
            return SignedJWT.parse(token);
        } catch (ParseException e) {
            throw new InvalidTokenException("Malformed action token", e);
        }
    }

    private static void verifySignature(SignedJWT jwt, String hmacSecret) {
        if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
            throw new InvalidTokenException("Unsupported signing algorithm");
        }
        try {
            if (!jwt.verify(new MACVerifier(hmacSecret.getBytes(StandardCharsets.UTF_8)))) {
                throw new InvalidTokenException("Signature verification failed");
            }
        } catch (JOSEException e) {
            throw new InvalidTokenException("Signature verification failed", e);
        }
    }

    private static JWTClaimsSet claimsOf(SignedJWT jwt) {
        try {
            return jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new InvalidTokenException("Malformed claim set", e);
        }
    }

    private static void verifyTimeWindow(JWTClaimsSet claims, Instant now) {
        Date expiresAt = claims.getExpirationTime();
        if (expiresAt == null || !expiresAt.toInstant().isAfter(now)) {
            throw new InvalidTokenException("Token expired");
        }
        Date notBefore = claims.getNotBeforeTime();
        if (notBefore != null && notBefore.toInstant().isAfter(now)) {
            throw new InvalidTokenException("Token is not yet valid");
        }
    }

    private static Map<String, String> toClaimMap(JWTClaimsSet claims) {
        Map<String, String> result = new LinkedHashMap<>();
        claims.getClaims().forEach((name, value) -> {
            String text = value instanceof Date date
                    ? String.valueOf(date.toInstant().getEpochSecond())
                    : String.valueOf(value);
            result.put(name, text);
        });
        return Map.copyOf(result);
    }
}
