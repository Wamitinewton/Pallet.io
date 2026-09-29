package io.pallet.gitintegration.config;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Refuses to start the service with weak or malformed key material, and exposes the GitHub App private key as a
 * parsed {@link PrivateKey}. Every failure names the property and never its value or length.
 */
@Configuration(proxyBeanMethods = false)
public class SecretMaterialValidator {

    static final String PRIVATE_KEY = "pallet.git.github.private-key";
    static final String CLIENT_SECRET = "pallet.git.github.client-secret";
    static final String WEBHOOK_SECRETS = "pallet.git.github.webhook-secrets";
    static final String STATE_SIGNING_KEY = "pallet.git.authorization.state-signing-key";
    static final String SESSION_KEY = "pallet.git.user-session.encryption-key";

    private static final int MIN_RSA_BITS = 2048;
    private static final int MIN_SECRET_BYTES = 32;
    private static final int SESSION_KEY_BYTES = 32;

    private static final String PKCS8_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PKCS8_FOOTER = "-----END PRIVATE KEY-----";
    private static final String PKCS1_HEADER = "-----BEGIN RSA PRIVATE KEY-----";
    private static final String PKCS1_FOOTER = "-----END RSA PRIVATE KEY-----";

    /** DER prefix of a PKCS#8 AlgorithmIdentifier for rsaEncryption (1.2.840.113549.1.1.1) with NULL parameters. */
    private static final byte[] RSA_ALGORITHM_IDENTIFIER = {
        0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
    };

    @Bean
    PrivateKey githubAppPrivateKey(GitIntegrationProperties properties) {
        validate(properties);
        return parsePrivateKey(properties.github().privateKey());
    }

    static void validate(GitIntegrationProperties properties) {
        requirePresent(CLIENT_SECRET, properties.github().clientSecret());
        requireWebhookSecrets(properties.github().webhookSecrets());
        byte[] stateKey =
                decodeBase64(STATE_SIGNING_KEY, properties.authorization().stateSigningKey());
        if (stateKey.length < MIN_SECRET_BYTES) {
            throw invalid(STATE_SIGNING_KEY, "must decode to at least " + MIN_SECRET_BYTES + " bytes");
        }
        byte[] sessionKey = decodeBase64(SESSION_KEY, properties.userSession().encryptionKey());
        if (sessionKey.length != SESSION_KEY_BYTES) {
            throw invalid(SESSION_KEY, "must decode to exactly " + SESSION_KEY_BYTES + " bytes (AES-256)");
        }
    }

    static RSAPrivateKey parsePrivateKey(String pem) {
        requirePresent(PRIVATE_KEY, pem);
        String normalized = pem.replace("\\n", "\n").strip();
        byte[] pkcs8;
        if (normalized.startsWith(PKCS8_HEADER) && normalized.endsWith(PKCS8_FOOTER)) {
            pkcs8 = decodePemBody(normalized, PKCS8_HEADER, PKCS8_FOOTER);
        } else if (normalized.startsWith(PKCS1_HEADER) && normalized.endsWith(PKCS1_FOOTER)) {
            pkcs8 = wrapPkcs1(decodePemBody(normalized, PKCS1_HEADER, PKCS1_FOOTER));
        } else {
            throw notAnRsaKey();
        }
        RSAPrivateKey key;
        try {
            key = (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException | ClassCastException e) {
            throw notAnRsaKey();
        }
        if (key.getModulus().bitLength() < MIN_RSA_BITS) {
            throw invalid(PRIVATE_KEY, "must be an RSA key of at least " + MIN_RSA_BITS + " bits");
        }
        return key;
    }

    private static void requireWebhookSecrets(List<String> secrets) {
        boolean anyPresent = false;
        for (int i = 0; i < secrets.size(); i++) {
            String secret = secrets.get(i);
            if (isMissing(secret)) {
                continue;
            }
            anyPresent = true;
            if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
                throw invalid(WEBHOOK_SECRETS + "[" + i + "]", "must be at least " + MIN_SECRET_BYTES + " bytes");
            }
        }
        if (!anyPresent) {
            throw invalid(WEBHOOK_SECRETS, "must contain at least one secret");
        }
    }

    private static byte[] decodePemBody(String pem, String header, String footer) {
        String body = pem.substring(header.length(), pem.length() - footer.length());
        try {
            return Base64.getMimeDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw notAnRsaKey();
        }
    }

    private static byte[] decodeBase64(String property, String value) {
        requirePresent(property, value);
        try {
            return Base64.getDecoder().decode(value.strip());
        } catch (IllegalArgumentException e) {
            throw invalid(property, "must be base64");
        }
    }

    /** Wraps a PKCS#1 RSAPrivateKey in a PKCS#8 PrivateKeyInfo, which is what the JDK's KeyFactory reads. */
    private static byte[] wrapPkcs1(byte[] pkcs1) {
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        info.writeBytes(new byte[] {0x02, 0x01, 0x00});
        info.writeBytes(RSA_ALGORITHM_IDENTIFIER);
        info.write(0x04);
        info.writeBytes(derLength(pkcs1.length));
        info.writeBytes(pkcs1);
        byte[] content = info.toByteArray();
        ByteArrayOutputStream sequence = new ByteArrayOutputStream();
        sequence.write(0x30);
        sequence.writeBytes(derLength(content.length));
        sequence.writeBytes(content);
        return sequence.toByteArray();
    }

    private static byte[] derLength(int length) {
        if (length < 0x80) {
            return new byte[] {(byte) length};
        }
        int bytes = length > 0xFFFF ? 3 : length > 0xFF ? 2 : 1;
        byte[] encoded = new byte[bytes + 1];
        encoded[0] = (byte) (0x80 | bytes);
        for (int i = bytes; i > 0; i--) {
            encoded[i] = (byte) (length & 0xFF);
            length >>= 8;
        }
        return encoded;
    }

    private static void requirePresent(String property, String value) {
        if (isMissing(value)) {
            throw invalid(property, "is required");
        }
    }

    /** An unset environment variable reaches binding as its literal, unresolved placeholder. */
    public static boolean isMissing(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String stripped = value.strip();
        return stripped.startsWith("${") && stripped.endsWith("}");
    }

    private static IllegalStateException notAnRsaKey() {
        return invalid(
                PRIVATE_KEY,
                "must be an RSA private key in PEM form: PKCS#8 (BEGIN PRIVATE KEY) or PKCS#1 (BEGIN RSA PRIVATE KEY)");
    }

    private static IllegalStateException invalid(String property, String requirement) {
        return new IllegalStateException(property + " " + requirement);
    }
}
