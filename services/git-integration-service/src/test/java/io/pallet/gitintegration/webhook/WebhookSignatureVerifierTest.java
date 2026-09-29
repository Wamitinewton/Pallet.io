package io.pallet.gitintegration.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.annotations.UnitTest;
import io.pallet.gitintegration.support.TestSecrets;
import io.pallet.gitintegration.support.WebhookFixtures;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class WebhookSignatureVerifierTest {

    private static final String CURRENT = TestSecrets.hex(32);
    private static final String PREVIOUS = TestSecrets.hex(32);
    private static final byte[] BODY = WebhookFixtures.load("push-main.json");

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(List.of(CURRENT, PREVIOUS));

    @Test
    void theCurrentSecretPassesAndIsReportedAsCurrent() {
        assertThat(verifier.verify(BODY, WebhookFixtures.sign(BODY, CURRENT)))
                .contains(WebhookSignatureVerifier.CURRENT);
    }

    @Test
    void thePreviousSecretPassesAndIsReportedAsPrevious() {
        assertThat(verifier.verify(BODY, WebhookFixtures.sign(BODY, PREVIOUS)))
                .contains(WebhookSignatureVerifier.PREVIOUS);
    }

    @Test
    void aSignatureUnderAnotherSecretFails() {
        assertThat(verifier.verify(BODY, WebhookFixtures.sign(BODY, TestSecrets.hex(32))))
                .isEmpty();
    }

    @Test
    void aSignatureWithoutThePrefixFails() {
        String signature = WebhookFixtures.sign(BODY, CURRENT);

        assertThat(verifier.verify(BODY, signature.substring("sha256=".length())))
                .isEmpty();
        assertThat(verifier.verify(BODY, signature.replace("sha256=", "sha1="))).isEmpty();
    }

    @Test
    void uppercaseHexFails() {
        String signature = WebhookFixtures.sign(BODY, CURRENT);
        String upper = "sha256=" + signature.substring("sha256=".length()).toUpperCase(Locale.ROOT);

        assertThat(verifier.verify(BODY, upper)).isEmpty();
    }

    @Test
    void aTruncatedOrExtendedDigestFails() {
        String signature = WebhookFixtures.sign(BODY, CURRENT);

        assertThat(verifier.verify(BODY, signature.substring(0, signature.length() - 2)))
                .isEmpty();
        assertThat(verifier.verify(BODY, signature + "00")).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "sha256=", "sha256=zz"})
    void aMissingOrEmptyHeaderFails(String header) {
        assertThat(verifier.verify(BODY, header)).isEmpty();
    }

    @Test
    void aValidSignatureOverABodyWithOneByteChangedFails() {
        String signature = WebhookFixtures.sign(BODY, CURRENT);
        byte[] changed = Arrays.copyOf(BODY, BODY.length);
        changed[changed.length / 2] ^= 0x01;

        assertThat(verifier.verify(changed, signature)).isEmpty();
    }

    @Test
    void withNoPreviousSecretConfiguredOnlyTheCurrentIsTried() {
        WebhookSignatureVerifier currentOnly = new WebhookSignatureVerifier(List.of(CURRENT, ""));

        assertThat(currentOnly.verify(BODY, WebhookFixtures.sign(BODY, CURRENT)))
                .contains(WebhookSignatureVerifier.CURRENT);
        assertThat(currentOnly.verify(BODY, WebhookFixtures.sign(BODY, PREVIOUS)))
                .isEmpty();
    }

    @Test
    void anUnresolvedPlaceholderIsNeverUsedAsASecret() {
        String placeholder = "${PALLET_GIT_WEBHOOK_SECRET_PREVIOUS}";
        WebhookSignatureVerifier withPlaceholder = new WebhookSignatureVerifier(List.of(CURRENT, placeholder));

        assertThat(withPlaceholder.verify(BODY, WebhookFixtures.sign(BODY, placeholder)))
                .isEmpty();
    }

    @Test
    void noUsableSecretRefusesToConstruct() {
        assertThatThrownBy(() -> new WebhookSignatureVerifier(List.of("", " ")))
                .isInstanceOf(IllegalStateException.class);
    }
}
