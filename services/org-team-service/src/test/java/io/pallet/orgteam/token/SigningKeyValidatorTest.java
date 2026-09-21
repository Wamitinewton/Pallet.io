package io.pallet.orgteam.token;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.annotations.UnitTest;
import io.pallet.orgteam.config.InviteSigningProperties;
import org.junit.jupiter.api.Test;

@UnitTest
class SigningKeyValidatorTest {

    private static void validate(String key) {
        new SigningKeyValidator(new InviteSigningProperties(key)).afterPropertiesSet();
    }

    @Test
    void aKeyOfThirtyTwoBytesPasses() {
        assertThatCode(() -> validate("k".repeat(32))).doesNotThrowAnyException();
    }

    @Test
    void aKeyOfThirtyOneBytesFails() {
        assertThatThrownBy(() -> validate("k".repeat(31)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pallet.invites.signing-key")
                .hasMessageContaining("32");
    }

    @Test
    void multiByteCharactersCountAsBytes() {
        assertThatCode(() -> validate("é".repeat(16))).doesNotThrowAnyException();
    }

    @Test
    void aBlankOrMissingKeyFails() {
        assertThatThrownBy(() -> validate("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validate("   ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validate(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theFailureMessageNeverContainsTheKey() {
        String weak = "s3cret-weak-key";

        assertThatThrownBy(() -> validate(weak)).hasMessageNotContaining(weak);
    }
}
