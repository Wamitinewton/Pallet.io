package io.pallet.gitintegration.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.annotations.UnitTest;
import org.junit.jupiter.api.Test;

@UnitTest
class HookDeliveryCursorTest {

    @Test
    void theNextCursorComesFromTheNextRelationOnly() {
        String link = "<https://api.github.com/app/hook/deliveries?per_page=100&cursor=v1_12077215967>; rel=\"next\","
                + " <https://api.github.com/app/hook/deliveries?per_page=100>; rel=\"first\"";

        assertThat(GitHubClient.nextCursor(link)).isEqualTo("v1_12077215967");
    }

    @Test
    void theLastPageHasNoNextCursor() {
        assertThat(GitHubClient.nextCursor(null)).isNull();
        assertThat(GitHubClient.nextCursor("<https://api.github.com/app/hook/deliveries?per_page=100>; rel=\"first\""))
                .isNull();
    }

    @Test
    void aCursorOutsideTheExpectedAlphabetIsRefused() {
        assertThatThrownBy(() -> GitHubClient.nextCursor(
                        "<https://api.github.com/app/hook/deliveries?cursor=a%2Fb%3Fc>; rel=\"next\""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> GitHubClient.nextCursor("https://api.github.com/app/hook/deliveries; rel=\"next\""))
                .isInstanceOf(IllegalStateException.class);
    }
}
