package io.pallet.gitintegration.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.UnitTest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

@UnitTest
class SessionCipherTest {

    private static final String KEY = "git:user-session:user-1";
    private static final byte[] PLAINTEXT = "{\"t\":\"gho_secret\"}".getBytes(StandardCharsets.UTF_8);

    private final SessionCipher cipher = new SessionCipher(key());

    @Test
    void roundTrips() {
        assertThat(cipher.decrypt(cipher.encrypt(PLAINTEXT, KEY), KEY)).hasValue(PLAINTEXT);
    }

    @Test
    void everyEncryptionUsesAFreshIv() {
        String first = cipher.encrypt(PLAINTEXT, KEY);
        String second = cipher.encrypt(PLAINTEXT, KEY);

        assertThat(first).isNotEqualTo(second);
        assertThat(iv(first)).isNotEqualTo(iv(second));
        assertThat(first).doesNotContain("gho_secret");
    }

    @Test
    void aCiphertextMovedToAnotherKeyDoesNotDecrypt() {
        String sealed = cipher.encrypt(PLAINTEXT, KEY);

        assertThat(cipher.decrypt(sealed, "git:user-session:user-2")).isEmpty();
    }

    @Test
    void aFlippedBitDoesNotDecrypt() {
        byte[] envelope = Base64.getDecoder().decode(cipher.encrypt(PLAINTEXT, KEY));
        envelope[envelope.length - 5] ^= 0x01;

        assertThat(cipher.decrypt(Base64.getEncoder().encodeToString(envelope), KEY))
                .isEmpty();
    }

    @Test
    void anotherKeyDoesNotDecrypt() {
        String sealed = cipher.encrypt(PLAINTEXT, KEY);

        assertThat(new SessionCipher(key()).decrypt(sealed, KEY)).isEmpty();
    }

    @Test
    void anythingThatIsNotAnEnvelopeIsAbsent() {
        assertThat(cipher.decrypt("not base64!", KEY)).isEmpty();
        assertThat(cipher.decrypt(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}), KEY))
                .isEmpty();
        byte[] otherVersion = Base64.getDecoder().decode(cipher.encrypt(PLAINTEXT, KEY));
        otherVersion[0] = 2;
        assertThat(cipher.decrypt(Base64.getEncoder().encodeToString(otherVersion), KEY))
                .isEmpty();
    }

    private static byte[] iv(String sealed) {
        byte[] envelope = Base64.getDecoder().decode(sealed);
        byte[] iv = new byte[12];
        System.arraycopy(envelope, 1, iv, 0, iv.length);
        return iv;
    }

    private static byte[] key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
