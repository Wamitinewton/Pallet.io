package io.pallet.gitintegration.session;

import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.config.SecretMaterialValidator;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for sessions at rest: {@code base64(version || iv || ciphertext+tag)}, a fresh 96-bit IV per encryption,
 * and the Redis key as associated data, so a value copied under another user's key fails to decrypt.
 */
@Component
public class SessionCipher {

    static final byte VERSION = 1;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    SessionCipher(GitIntegrationProperties properties) {
        this(decodeKey(properties.userSession().encryptionKey()));
    }

    SessionCipher(byte[] key) {
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("A session key is exactly " + KEY_BYTES + " bytes");
        }
        this.key = new SecretKeySpec(key, "AES");
    }

    String encrypt(byte[] plaintext, String associatedData) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext);
            return Base64.getEncoder()
                    .encodeToString(ByteBuffer.allocate(1 + IV_BYTES + sealed.length)
                            .put(VERSION)
                            .put(iv)
                            .put(sealed)
                            .array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /** @return empty for anything that doesn't decrypt under this key and associated data, e.g. after a key rotation */
    Optional<byte[]> decrypt(String encoded, String associatedData) {
        byte[] envelope;
        try {
            envelope = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (envelope.length <= 1 + IV_BYTES || envelope[0] != VERSION) {
            return Optional.empty();
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, envelope, 1, IV_BYTES));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return Optional.of(cipher.doFinal(envelope, 1 + IV_BYTES, envelope.length - 1 - IV_BYTES));
        } catch (GeneralSecurityException e) {
            return Optional.empty();
        }
    }

    private static byte[] decodeKey(String value) {
        if (SecretMaterialValidator.isMissing(value)) {
            throw new IllegalStateException("pallet.git.user-session.encryption-key is required");
        }
        try {
            return Base64.getDecoder().decode(value.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("pallet.git.user-session.encryption-key must be base64");
        }
    }
}
