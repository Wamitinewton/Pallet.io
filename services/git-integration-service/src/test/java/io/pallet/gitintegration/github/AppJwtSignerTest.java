package io.pallet.gitintegration.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import io.pallet.common.test.annotations.UnitTest;
import io.pallet.gitintegration.support.MutableClock;
import io.pallet.gitintegration.support.TestSecrets;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

@UnitTest
class AppJwtSignerTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final String CLIENT_ID = "Iv1.test-client";

    private final MutableClock clock = new MutableClock(NOW);
    private final AppJwtSigner signer = new AppJwtSigner(TestSecrets.APP_KEY.getPrivate(), CLIENT_ID, clock);

    @Test
    void theClaimsAreWhatGitHubExpects() throws Exception {
        SignedJWT jwt = SignedJWT.parse(signer.current().value());

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo(CLIENT_ID);
        assertThat(jwt.getJWTClaimsSet().getIssueTime().toInstant()).isEqualTo(NOW.minusSeconds(60));
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(9)));
    }

    @Test
    void theSignatureVerifiesWithTheAppsPublicKey() throws Exception {
        SignedJWT jwt = SignedJWT.parse(signer.current().value());

        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) TestSecrets.APP_KEY.getPublic())))
                .isTrue();
    }

    @Test
    void itIsReusedUntilAMinuteBeforeExpiryThenReMinted() {
        AppJwt first = signer.current();

        clock.advance(Duration.ofMinutes(7).plusSeconds(59));
        assertThat(signer.current()).isSameAs(first);

        clock.advance(Duration.ofSeconds(1));
        AppJwt second = signer.current();
        assertThat(second).isNotSameAs(first);
        assertThat(second.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(17)));
    }

    @Test
    void toStringNeverShowsTheJwt() {
        AppJwt jwt = signer.current();

        assertThat(jwt.toString()).doesNotContain(jwt.value());
        assertThat(new GitHubCredential.App(jwt).toString()).doesNotContain(jwt.value());
    }
}
