package io.pallet.orgteam.invite;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
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
 * Copy of {@code SignedActionToken.verify} from identity-service, kept verbatim in behaviour so the
 * compatibility test fails when the two services' wire formats drift. Update it with that class.
 */
final class IdentityActionTokenVerifier {

    private IdentityActionTokenVerifier() {}

    static Map<String, String> verify(String purpose, String token, String hmacSecret) {
        try {
            SignedJWT signedJwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(signedJwt.getHeader().getAlgorithm())) {
                throw new IllegalArgumentException("Unsupported signing algorithm");
            }
            if (!signedJwt.verify(new MACVerifier(hmacSecret.getBytes(StandardCharsets.UTF_8)))) {
                throw new IllegalArgumentException("Signature verification failed");
            }
            JWTClaimsSet claims = signedJwt.getJWTClaimsSet();
            Date expiresAt = claims.getExpirationTime();
            if (expiresAt == null || expiresAt.toInstant().isBefore(Instant.now())) {
                throw new IllegalArgumentException("Token expired");
            }
            if (!purpose.equals(claims.getClaim("purpose"))) {
                throw new IllegalArgumentException("Token purpose mismatch");
            }
            Map<String, String> result = new LinkedHashMap<>();
            claims.getClaims().forEach((name, value) -> {
                if (!"exp".equals(name)) {
                    result.put(name, String.valueOf(value));
                }
            });
            return Map.copyOf(result);
        } catch (ParseException | JOSEException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
