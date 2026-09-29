package io.pallet.gitintegration.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.annotations.UnitTest;
import io.pallet.gitintegration.support.TestSecrets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@UnitTest
class SecretMaterialValidatorTest {

    @Test
    void validMaterialPassesAndYieldsTheRsaKey() {
        GitIntegrationProperties properties = bind(validProperties());

        SecretMaterialValidator.validate(properties);
        RSAPrivateKey key = SecretMaterialValidator.parsePrivateKey(TestSecrets.PRIVATE_KEY_PEM);

        assertThat(key.getModulus()).isEqualTo(((RSAPrivateKey) TestSecrets.APP_KEY.getPrivate()).getModulus());
    }

    @Test
    void aPkcs1PemAsGitHubDownloadsItIsAccepted() {
        RSAPrivateKey expected = (RSAPrivateKey) TestSecrets.APP_KEY.getPrivate();
        String pkcs1 = TestSecrets.pem("RSA PRIVATE KEY", pkcs1Of(expected));

        RSAPrivateKey parsed = SecretMaterialValidator.parsePrivateKey(pkcs1);

        assertThat(parsed.getModulus()).isEqualTo(expected.getModulus());
        assertThat(parsed.getPrivateExponent()).isEqualTo(expected.getPrivateExponent());
    }

    @Test
    void aPemWithEscapedNewlinesFromAnEnvironmentVariableIsAccepted() {
        String escaped = TestSecrets.PRIVATE_KEY_PEM.replace("\n", "\\n");

        assertThat(SecretMaterialValidator.parsePrivateKey(escaped)).isNotNull();
    }

    @Test
    void a1024BitRsaKeyIsRejected() {
        String weak = TestSecrets.pkcs8Pem(TestSecrets.rsaKeyPair(1024).getPrivate());

        assertRejected(() -> SecretMaterialValidator.parsePrivateKey(weak), SecretMaterialValidator.PRIVATE_KEY, weak)
                .hasMessageContaining("2048");
    }

    @Test
    void anEcKeyIsRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        KeyPair ec = generator.generateKeyPair();
        String pem = TestSecrets.pkcs8Pem(ec.getPrivate());

        assertRejected(() -> SecretMaterialValidator.parsePrivateKey(pem), SecretMaterialValidator.PRIVATE_KEY, pem)
                .hasMessageContaining("RSA");
    }

    @Test
    void garbagePemIsRejected() {
        String garbage = "-----BEGIN PRIVATE KEY-----\nbm90IGEga2V5IGF0IGFsbA==\n-----END PRIVATE KEY-----";

        assertRejected(
                () -> SecretMaterialValidator.parsePrivateKey(garbage), SecretMaterialValidator.PRIVATE_KEY, garbage);
    }

    @Test
    void anUnrecognisedPemTypeIsRejected() {
        String certificate = "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----";

        assertRejected(
                        () -> SecretMaterialValidator.parsePrivateKey(certificate),
                        SecretMaterialValidator.PRIVATE_KEY,
                        certificate)
                .hasMessageContaining("PKCS#1");
    }

    @Test
    void aMissingPrivateKeyIsRejected() {
        assertThatThrownBy(() -> SecretMaterialValidator.parsePrivateKey(null))
                .hasMessageContaining(SecretMaterialValidator.PRIVATE_KEY);
    }

    @Test
    void anUnresolvedEnvironmentPlaceholderIsReportedAsMissing() {
        assertThatThrownBy(() -> SecretMaterialValidator.parsePrivateKey("${PALLET_GIT_GITHUB_PRIVATE_KEY}"))
                .hasMessage(SecretMaterialValidator.PRIVATE_KEY + " is required");

        Map<String, Object> values = validProperties();
        values.put("pallet.git.user-session.encryption-key", "${PALLET_GIT_USER_SESSION_KEY}");
        assertThatThrownBy(() -> SecretMaterialValidator.validate(bind(values)))
                .hasMessage(SecretMaterialValidator.SESSION_KEY + " is required");
    }

    @Test
    void anUnresolvedPreviousWebhookSecretIsIgnored() {
        Map<String, Object> values = validProperties();
        values.put("pallet.git.github.webhook-secrets[1]", "${PALLET_GIT_WEBHOOK_SECRET_PREVIOUS}");

        SecretMaterialValidator.validate(bind(values));
    }

    @Test
    void aSixteenByteWebhookSecretIsRejected() {
        String shortSecret = TestSecrets.hex(8);
        Map<String, Object> values = validProperties();
        values.put("pallet.git.github.webhook-secrets[1]", shortSecret);

        assertRejected(
                () -> SecretMaterialValidator.validate(bind(values)),
                SecretMaterialValidator.WEBHOOK_SECRETS + "[1]",
                shortSecret);
    }

    @Test
    void anEmptyPreviousWebhookSecretIsIgnored() {
        Map<String, Object> values = validProperties();
        values.put("pallet.git.github.webhook-secrets[1]", "");

        SecretMaterialValidator.validate(bind(values));
    }

    @Test
    void noWebhookSecretAtAllIsRejected() {
        Map<String, Object> values = validProperties();
        values.put("pallet.git.github.webhook-secrets[0]", "");
        values.put("pallet.git.github.webhook-secrets[1]", "");

        assertThatThrownBy(() -> SecretMaterialValidator.validate(bind(values)))
                .hasMessageContaining(SecretMaterialValidator.WEBHOOK_SECRETS);
    }

    @Test
    void aThirtyOneByteSessionKeyIsRejected() {
        String shortKey = TestSecrets.base64(31);
        Map<String, Object> values = validProperties();
        values.put("pallet.git.user-session.encryption-key", shortKey);

        assertRejected(
                () -> SecretMaterialValidator.validate(bind(values)), SecretMaterialValidator.SESSION_KEY, shortKey);
    }

    @Test
    void aThirtyThreeByteSessionKeyIsRejected() {
        String longKey = TestSecrets.base64(33);
        Map<String, Object> values = validProperties();
        values.put("pallet.git.user-session.encryption-key", longKey);

        assertRejected(
                () -> SecretMaterialValidator.validate(bind(values)), SecretMaterialValidator.SESSION_KEY, longKey);
    }

    @Test
    void aShortStateSigningKeyIsRejected() {
        String shortKey = TestSecrets.base64(16);
        Map<String, Object> values = validProperties();
        values.put("pallet.git.authorization.state-signing-key", shortKey);

        assertRejected(
                () -> SecretMaterialValidator.validate(bind(values)),
                SecretMaterialValidator.STATE_SIGNING_KEY,
                shortKey);
    }

    @Test
    void aStateSigningKeyThatIsNotBase64IsRejected() {
        String notBase64 = "not base64 at all, but long enough to pass a length check!";
        Map<String, Object> values = validProperties();
        values.put("pallet.git.authorization.state-signing-key", notBase64);

        assertRejected(
                () -> SecretMaterialValidator.validate(bind(values)),
                SecretMaterialValidator.STATE_SIGNING_KEY,
                notBase64);
    }

    @Test
    void theContextRefusesToStartWithAWeakKeyAndExposesThePrivateKeyWhenValid() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(Wiring.class)
                .withPropertyValues(asPairs(validProperties()));

        runner.run(context -> assertThat(context.getBean(PrivateKey.class)).isInstanceOf(RSAPrivateKey.class));

        String weak = TestSecrets.pkcs8Pem(TestSecrets.rsaKeyPair(1024).getPrivate());
        runner.withPropertyValues("pallet.git.github.private-key=" + weak).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining(SecretMaterialValidator.PRIVATE_KEY)
                    .hasMessageNotContaining(pemBody(weak)[1]);
        });
    }

    private static AbstractThrowableAssert<?, ? extends Throwable> assertRejected(
            Runnable action, String property, String secret) {
        AbstractThrowableAssert<?, ? extends Throwable> rejection = assertThatThrownBy(action::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith(property + " ")
                .hasNoCause()
                .hasMessageNotContaining(secret);
        for (String line : pemBody(secret)) {
            if (line.length() >= 8) {
                rejection.hasMessageNotContaining(line);
            }
        }
        rejection.hasMessageNotContaining(String.valueOf(secret.length()));
        return rejection;
    }

    private static String[] pemBody(String value) {
        return Arrays.stream(value.split("\n"))
                .filter(line -> !line.startsWith("-----"))
                .toArray(String[]::new);
    }

    private static Map<String, Object> validProperties() {
        Map<String, Object> values = new HashMap<>(TestSecrets.properties());
        values.put("pallet.git.github.app-id", "1");
        values.put("pallet.git.github.client-id", "client");
        values.put("pallet.git.github.app-slug", "pallet");
        return values;
    }

    private static GitIntegrationProperties bind(Map<String, Object> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("pallet.git", GitIntegrationProperties.class);
    }

    private static String[] asPairs(Map<String, Object> values) {
        return values.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }

    /** Extracts the PKCS#1 RSAPrivateKey the JDK wraps inside its PKCS#8 encoding. */
    private static byte[] pkcs1Of(PrivateKey key) {
        byte[] der = key.getEncoded();
        int[] cursor = {0};
        readHeader(der, cursor);
        skipElement(der, cursor);
        skipElement(der, cursor);
        int length = readHeader(der, cursor);
        return Arrays.copyOfRange(der, cursor[0], cursor[0] + length);
    }

    private static void skipElement(byte[] der, int[] cursor) {
        int length = readHeader(der, cursor);
        cursor[0] += length;
    }

    private static int readHeader(byte[] der, int[] cursor) {
        cursor[0]++;
        int first = der[cursor[0]++] & 0xFF;
        if (first < 0x80) {
            return first;
        }
        int length = 0;
        for (int i = 0; i < (first & 0x7F); i++) {
            length = (length << 8) | (der[cursor[0]++] & 0xFF);
        }
        return length;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GitIntegrationProperties.class)
    @Import(SecretMaterialValidator.class)
    static class Wiring {}
}
