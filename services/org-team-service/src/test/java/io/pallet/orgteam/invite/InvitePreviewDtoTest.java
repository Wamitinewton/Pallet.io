package io.pallet.orgteam.invite;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.UnitTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@UnitTest
class InvitePreviewDtoTest {

    @ParameterizedTest
    @CsvSource({
        "jane@example.com, j***@example.com",
        "a@b.io, a***@b.io",
        "very.long.local.part@example.co.uk, v***@example.co.uk",
        "@nolocal.io, ***",
        "no-at-sign, ***"
    })
    void onlyTheFirstCharacterOfTheLocalPartSurvivesMasking(String email, String masked) {
        assertThat(InvitePreviewDto.mask(email)).isEqualTo(masked);
    }
}
