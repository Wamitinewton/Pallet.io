package io.pallet.gitintegration.repolink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.annotations.UnitTest;
import io.pallet.gitintegration.repolink.RepoLinkExceptions.InvalidRootDirectoryException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class RootDirectoryTest {

    @Test
    void nullAndEmptyAreTheRepositoryRoot() {
        assertThat(RootDirectory.normalize(null)).isNull();
        assertThat(RootDirectory.normalize("")).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "apps/web,apps/web",
        "apps/web/,apps/web",
        "web,web",
        "services/api-v2,services/api-v2",
        ".github,.github"
    })
    void aRelativeDirectoryIsKeptWithoutItsTrailingSlash(String value, String normalized) {
        assertThat(RootDirectory.normalize(value)).isEqualTo(normalized);
    }

    @Test
    void theLongestAllowedDirectoryIsAccepted() {
        String longest = "a".repeat(RootDirectory.MAX_LENGTH);

        assertThat(RootDirectory.normalize(longest)).isEqualTo(longest);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/apps/web",
                "/",
                "../web",
                "apps/../web",
                "apps/..",
                "apps/web..old",
                "./apps",
                "apps/./web",
                ".",
                "apps\\web",
                "apps//web",
                "apps/web//",
                "apps/\u0000web",
                "apps/\nweb",
                "apps/\u007fweb"
            })
    void everyRuleIsEnforced(String value) {
        assertThatThrownBy(() -> RootDirectory.normalize(value))
                .isInstanceOf(InvalidRootDirectoryException.class)
                .satisfies(e -> assertThat(((InvalidRootDirectoryException) e).getErrorCode())
                        .isEqualTo("INVALID_ROOT_DIRECTORY"));
    }

    @Test
    void longerThan255CharactersIsRejected() {
        assertThatThrownBy(() -> RootDirectory.normalize("a".repeat(RootDirectory.MAX_LENGTH + 1)))
                .isInstanceOf(InvalidRootDirectoryException.class)
                .hasMessageContaining("255");
    }
}
