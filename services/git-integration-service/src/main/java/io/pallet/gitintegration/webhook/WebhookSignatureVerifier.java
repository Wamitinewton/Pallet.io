package io.pallet.gitintegration.webhook;

import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.config.SecretMaterialValidator;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Checks {@code X-Hub-Signature-256} over the raw body against the current and the previous webhook secret, so the
 * secret can be rotated without dropping deliveries.
 */
@Component
public class WebhookSignatureVerifier {

    public static final String CURRENT = "current";
    public static final String PREVIOUS = "previous";

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";
    private static final int DIGEST_HEX_LENGTH = 64;
    private static final byte[] MALFORMED = new byte[0];

    private final List<Secret> secrets;

    @Autowired
    WebhookSignatureVerifier(GitIntegrationProperties properties) {
        this(properties.github().webhookSecrets());
    }

    /** {@code configured} is in rotation order: the current secret first, then the previous one. Unset entries are skipped. */
    WebhookSignatureVerifier(List<String> configured) {
        List<Secret> usable = new ArrayList<>();
        for (int i = 0; i < configured.size(); i++) {
            String secret = configured.get(i);
            if (!SecretMaterialValidator.isMissing(secret)) {
                usable.add(new Secret(
                        i == 0 ? CURRENT : PREVIOUS,
                        new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM)));
            }
        }
        if (usable.isEmpty()) {
            throw new IllegalStateException("No webhook secret is configured");
        }
        this.secrets = List.copyOf(usable);
    }

    /**
     * Computes the HMAC under every configured secret before deciding, so the timing doesn't reveal which one matched.
     *
     * @return which secret matched, or empty if the header is missing, malformed, or matches none
     */
    public Optional<String> verify(byte[] body, String header) {
        byte[] expected = decode(header);
        if (expected.length == 0) {
            return Optional.empty();
        }
        String matched = null;
        for (Secret secret : secrets) {
            if (MessageDigest.isEqual(hmac(secret.key(), body), expected) && matched == null) {
                matched = secret.label();
            }
        }
        return Optional.ofNullable(matched);
    }

    private static byte[] decode(String header) {
        if (header == null || header.length() != PREFIX.length() + DIGEST_HEX_LENGTH || !header.startsWith(PREFIX)) {
            return MALFORMED;
        }
        for (int i = PREFIX.length(); i < header.length(); i++) {
            char c = header.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return MALFORMED;
            }
        }
        return HexFormat.of().parseHex(header, PREFIX.length(), header.length());
    }

    private static byte[] hmac(SecretKeySpec key, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private record Secret(String label, SecretKeySpec key) {}
}
