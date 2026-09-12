package io.pallet.identity.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.pallet.common.test.annotations.UnitTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@UnitTest
class SignedActionTokenTest {

    private static final String SECRET = "unit-test-hmac-secret-32-bytes-min!";
    private static final String OTHER_SECRET = "a-completely-different-32-byte-secret!!";
    private static final String PURPOSE = "invite";

    @Test
    void validSignatureUnexpiredAndMatchingPurposeReturnsClaimsIncludingJti() {
        String jti = UUID.randomUUID().toString();
        String token =
                issue(PURPOSE, Map.of("orgId", "org-1", "email", "owner@pallet.io"), jti, Duration.ofMinutes(15));

        Map<String, String> claims = SignedActionToken.verify(PURPOSE, token, SECRET);

        assertThat(claims)
                .containsEntry("jti", jti)
                .containsEntry("orgId", "org-1")
                .containsEntry("email", "owner@pallet.io");
    }

    @Test
    void wrongSigningSecretThrowsInvalidTokenException() {
        String token =
                issue(PURPOSE, Map.of("orgId", "org-1"), UUID.randomUUID().toString(), Duration.ofMinutes(15));

        assertThatThrownBy(() -> SignedActionToken.verify(PURPOSE, token, OTHER_SECRET))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void expiredTokenThrowsInvalidTokenException() {
        String token =
                issue(PURPOSE, Map.of("orgId", "org-1"), UUID.randomUUID().toString(), Duration.ofMinutes(-1));

        assertThatThrownBy(() -> SignedActionToken.verify(PURPOSE, token, SECRET))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void purposeMismatchThrowsInvalidTokenException() {
        String token =
                issue(PURPOSE, Map.of("orgId", "org-1"), UUID.randomUUID().toString(), Duration.ofMinutes(15));

        assertThatThrownBy(() -> SignedActionToken.verify("password-reset", token, SECRET))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void malformedTokenThrowsInvalidTokenExceptionNotAnUncheckedParseFailure() {
        assertThatThrownBy(() -> SignedActionToken.verify(PURPOSE, "not-a-jws", SECRET))
                .isInstanceOf(InvalidTokenException.class);
    }

    private static String issue(String purpose, Map<String, String> claims, String jti, Duration ttl) {
        try {
            JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder()
                    .claim("purpose", purpose)
                    .jwtID(jti)
                    .expirationTime(Date.from(Instant.now().plus(ttl)));
            claims.forEach(claimsBuilder::claim);

            SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsBuilder.build());
            signedJwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
            return signedJwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
