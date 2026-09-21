package io.pallet.orgteam.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.UnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class SlugsTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "Platform Team|platform-team",
                "  Platform   Team  |platform-team",
                "Backend & Data (EU)|backend-data-eu",
                "--leading and trailing--|leading-and-trailing",
                "Café Ñandú|cafe-nandu",
                "Team 42|team-42",
                "UPPER_case.name|upper-case-name"
            })
    void namesAreLowercasedAndSeparatedByHyphens(String name, String expected) {
        assertThat(Slugs.fromName(name)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"!!!", "---", "   ", "团队", ""})
    void namesWithNothingUsableFallBackToARandomValidSlug(String name) {
        String slug = Slugs.fromName(name);

        assertThat(slug).startsWith("slug-");
        assertThat(Slugs.isValid(slug)).isTrue();
        assertThat(Slugs.fromName(name)).isNotEqualTo(slug);
    }

    @Test
    void aVeryLongNameIsTruncatedWithoutTrailingHyphens() {
        String slug = Slugs.fromName("a".repeat(62) + " b" + "c".repeat(40));

        assertThat(slug).hasSize(62).isEqualTo("a".repeat(62));
        assertThat(Slugs.isValid(slug)).isTrue();
        assertThat(Slugs.fromName("x".repeat(200))).hasSize(Slugs.MAX_LENGTH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "a1", "team-1", "0team", "a-b-c", "abc123"})
    void wellFormedLabelsAreValid(String slug) {
        assertThat(Slugs.isValid(slug)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-a", "a-", "A", "a_b", "a b", "a.b", "é"})
    void malformedLabelsAreInvalid(String slug) {
        assertThat(Slugs.isValid(slug)).isFalse();
    }

    @Test
    void theLengthBoundaryIs63() {
        assertThat(Slugs.isValid("a".repeat(63))).isTrue();
        assertThat(Slugs.isValid("a".repeat(64))).isFalse();
        assertThat(Slugs.isValid(null)).isFalse();
    }
}
