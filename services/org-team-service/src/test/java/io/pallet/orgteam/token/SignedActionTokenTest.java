package io.pallet.orgteam.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import io.pallet.common.test.annotations.UnitTest;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.Test;

@UnitTest
class SignedActionTokenTest {

    private static final String SECRET = "unit-test-secret-that-is-comfortably-long";
    private static final Map<String, String> CLAIMS =
            Map.of("jti", "0b6f7a52-0000-4000-8000-000000000001", "orgId", "org-1", "email", "a@b.io", "role", "admin");

    private static Instant inOneHour() {
        return Instant.now().plus(1, ChronoUnit.HOURS);
    }

    private static String issue() {
        return token("invite", inOneHour(), SECRET);
    }

    private static String token(String purpose, Instant expiresAt, String secret) {
        return SignedActionToken.issue(purpose, CLAIMS, Instant.now(), expiresAt, secret);
    }

    @Test
    void aRoundTripReturnsEveryClaimAsAString() {
        Instant expiresAt = Instant.now().plusSeconds(3600).plusMillis(999);

        Map<String, String> verified =
                SignedActionToken.verify("invite", token("invite", expiresAt, SECRET), SECRET, Clock.systemUTC());

        assertThat(verified).containsAllEntriesOf(CLAIMS).containsEntry("purpose", "invite");
        assertThat(verified).containsKeys("iat", "exp");
        assertThat(verified.get("exp")).isEqualTo(String.valueOf(expiresAt.getEpochSecond()));
    }

    @Test
    void theIssueTimeIsTheOneSupplied() {
        Instant issuedAt = Instant.now().minusSeconds(120);

        String token = SignedActionToken.issue("invite", CLAIMS, issuedAt, inOneHour(), SECRET);

        assertThat(SignedActionToken.verify("invite", token, SECRET, Clock.systemUTC()))
                .containsEntry("iat", String.valueOf(issuedAt.getEpochSecond()));
    }

    @Test
    void aTamperedPayloadIsRejected() {
        String[] parts = issue().split("\\.");
        String forged = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"admin\"", "\"owner\"");
        String token = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(forged.getBytes(StandardCharsets.UTF_8)) + "."
                + parts[2];

        assertRejected(token, SECRET);
    }

    @Test
    void aTamperedSignatureIsRejected() {
        String token = issue();
        String flipped = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        assertRejected(flipped, SECRET);
    }

    @Test
    void theWrongSecretIsRejected() {
        assertRejected(issue(), "another-secret-that-is-comfortably-long-too");
    }

    @Test
    void anExpiredTokenIsRejected() {
        assertRejected(token("invite", Instant.now().minusSeconds(5), SECRET), SECRET);
    }

    @Test
    void expiryFollowsTheSuppliedClockNotTheSystemOne() {
        Instant expiresAt = Instant.now().plusSeconds(3600);
        String token = token("invite", expiresAt, SECRET);
        Clock afterExpiry = Clock.fixed(expiresAt.plusSeconds(1), java.time.ZoneOffset.UTC);

        assertThatThrownBy(() -> SignedActionToken.verify("invite", token, SECRET, afterExpiry))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void aTokenForAnotherPurposeIsRejected() {
        String token = token("password-reset", inOneHour(), SECRET);

        assertRejected(token, SECRET);
    }

    @Test
    void anUnsignedTokenIsRejected() {
        String unsigned = new PlainJWT(new JWTClaimsSet.Builder()
                        .claim("purpose", "invite")
                        .expirationTime(Date.from(inOneHour()))
                        .build())
                .serialize();

        assertRejected(unsigned, SECRET);
    }

    @Test
    void anAsymmetricallySignedTokenIsRejected() throws NoSuchAlgorithmException, JOSEException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.RS256),
                new JWTClaimsSet.Builder()
                        .claim("purpose", "invite")
                        .expirationTime(Date.from(inOneHour()))
                        .build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) pair.getPrivate()));

        assertRejected(jwt.serialize(), SECRET);
    }

    @Test
    void garbageIsRejected() {
        assertRejected("not-a-token", SECRET);
        assertRejected("", SECRET);
    }

    @Test
    void aRejectionNeverEchoesTheTokenToTheClient() {
        String token = issue();

        assertThatThrownBy(() -> SignedActionToken.verify(
                        "invite", token, "wrong-secret-wrong-secret-wrong-secret", Clock.systemUTC()))
                .isInstanceOfSatisfying(InvalidTokenException.class, e -> {
                    assertThat(e.getMessage()).doesNotContain(token);
                    assertThat(e.getStatus().value()).isEqualTo(400);
                    assertThat(e.getErrorCode()).isEqualTo("INVALID_TOKEN");
                });
    }

    private static void assertRejected(String token, String secret) {
        assertThatThrownBy(() -> SignedActionToken.verify("invite", token, secret, Clock.systemUTC()))
                .isInstanceOf(InvalidTokenException.class);
    }
}
