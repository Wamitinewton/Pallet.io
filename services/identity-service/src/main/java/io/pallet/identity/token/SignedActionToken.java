package io.pallet.identity.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Verifies a signed action token minted by another service against a shared HMAC secret — the
 * stateless half of the sync-free cross-service handoff described in
 * {@code docs/identity-service/ARCHITECTURE.md}. {@code identity-service} never mints one of
 * these tokens, only verifies them.
 *
 * <p>{@code verify} intentionally does not check single-use: that's state, and this is a pure,
 * stateless function of its three arguments. Replay protection against the returned {@code jti}
 * is the caller's responsibility.
 */
public final class SignedActionToken {

    private static final String PURPOSE_CLAIM = "purpose";
    private static final String EXPIRATION_CLAIM = "exp";

    private SignedActionToken() {}

    public static Map<String, String> verify(String purpose, String token, String hmacSecret) {
        SignedJWT signedJwt = parse(token);
        verifySignature(signedJwt, hmacSecret);
        JWTClaimsSet claims = claimsOf(signedJwt);
        verifyNotExpired(claims);
        verifyPurpose(purpose, claims);
        return toClaimMap(claims);
    }

    private static SignedJWT parse(String token) {
        try {
            return SignedJWT.parse(token);
        } catch (ParseException e) {
            throw new InvalidTokenException("Malformed action token", e);
        }
    }

    private static void verifySignature(SignedJWT signedJwt, String hmacSecret) {
        if (!JWSAlgorithm.HS256.equals(signedJwt.getHeader().getAlgorithm())) {
            throw new InvalidTokenException("Unsupported signing algorithm");
        }
        try {
            JWSVerifier verifier = new MACVerifier(hmacSecret.getBytes(StandardCharsets.UTF_8));
            if (!signedJwt.verify(verifier)) {
                throw new InvalidTokenException("Signature verification failed");
            }
        } catch (JOSEException e) {
            throw new InvalidTokenException("Signature verification failed", e);
        }
    }

    private static JWTClaimsSet claimsOf(SignedJWT signedJwt) {
        try {
            return signedJwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new InvalidTokenException("Malformed claim set", e);
        }
    }

    private static void verifyNotExpired(JWTClaimsSet claims) {
        Date expiresAt = claims.getExpirationTime();
        if (expiresAt == null || expiresAt.toInstant().isBefore(Instant.now())) {
            throw new InvalidTokenException("Token expired");
        }
    }

    private static void verifyPurpose(String expectedPurpose, JWTClaimsSet claims) {
        if (!expectedPurpose.equals(claims.getClaim(PURPOSE_CLAIM))) {
            throw new InvalidTokenException("Token purpose mismatch");
        }
    }

    private static Map<String, String> toClaimMap(JWTClaimsSet claims) {
        Map<String, String> result = new LinkedHashMap<>();
        claims.getClaims().forEach((name, value) -> {
            if (!EXPIRATION_CLAIM.equals(name)) {
                result.put(name, String.valueOf(value));
            }
        });
        return Map.copyOf(result);
    }
}
