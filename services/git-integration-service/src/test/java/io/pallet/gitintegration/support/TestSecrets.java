package io.pallet.gitintegration.support;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * Key material generated once per test JVM, so no key or secret ever sits in a tracked file. Registered for every
 * Spring test context through {@code META-INF/spring.factories}.
 */
public final class TestSecrets implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final SecureRandom RANDOM = new SecureRandom();

    public static final KeyPair APP_KEY = rsaKeyPair(2048);
    public static final String PRIVATE_KEY_PEM = pkcs8Pem(APP_KEY.getPrivate());
    public static final String WEBHOOK_SECRET = hex(32);
    public static final String PREVIOUS_WEBHOOK_SECRET = hex(32);
    public static final String CLIENT_SECRET = hex(20);
    public static final String STATE_SIGNING_KEY = base64(32);
    public static final String USER_SESSION_KEY = base64(32);

    public static Map<String, Object> properties() {
        return Map.of(
                "pallet.git.github.private-key", PRIVATE_KEY_PEM,
                "pallet.git.github.client-secret", CLIENT_SECRET,
                "pallet.git.github.webhook-secrets[0]", WEBHOOK_SECRET,
                "pallet.git.github.webhook-secrets[1]", PREVIOUS_WEBHOOK_SECRET,
                "pallet.git.authorization.state-signing-key", STATE_SIGNING_KEY,
                "pallet.git.user-session.encryption-key", USER_SESSION_KEY);
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("testSecrets", properties()));
    }

    public static KeyPair rsaKeyPair(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits, RANDOM);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String pkcs8Pem(PrivateKey key) {
        return pem("PRIVATE KEY", key.getEncoded());
    }

    public static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    public static String base64(int bytes) {
        return Base64.getEncoder().encodeToString(randomBytes(bytes));
    }

    public static String hex(int bytes) {
        return HexFormat.of().formatHex(randomBytes(bytes));
    }

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
