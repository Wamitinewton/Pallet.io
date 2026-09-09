package io.pallet.common.test.assertions;

import static io.pallet.common.test.assertions.PalletAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.api.PageResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageResponseAssertTest {

    private static PageResponse<String> firstPageOfThree() {
        return new PageResponse<>(List.of("a", "b"), 0, 2, 5, 3, true, false);
    }

    @Test
    void hasTotalElementsPassesSilentlyWhenTheCountMatches() {
        assertThat(firstPageOfThree()).hasTotalElements(5);
    }

    @Test
    void hasTotalElementsFailsWithTheActualCountWhenItDiffers() {
        PageResponse<String> page = firstPageOfThree();

        assertThatThrownBy(() -> assertThat(page).hasTotalElements(99))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("5");
    }

    @Test
    void isFirstPagePassesSilentlyWhenFirstIsTrue() {
        assertThat(firstPageOfThree()).isFirstPage();
    }

    @Test
    void isFirstPageFailsWhenFirstIsFalse() {
        PageResponse<String> notFirst = new PageResponse<>(List.of("c"), 1, 2, 5, 3, false, false);

        assertThatThrownBy(() -> assertThat(notFirst).isFirstPage())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("first page");
    }

    @Test
    void isLastPagePassesSilentlyWhenLastIsTrue() {
        PageResponse<String> lastPage = new PageResponse<>(List.of("e"), 2, 2, 5, 3, false, true);

        assertThat(lastPage).isLastPage();
    }

    @Test
    void isLastPageFailsWhenLastIsFalse() {
        PageResponse<String> page = firstPageOfThree();

        assertThatThrownBy(() -> assertThat(page).isLastPage())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("last page");
    }

    @Test
    void contentHandsBackAListAssertOverTheContent() {
        assertThat(firstPageOfThree()).content().containsExactly("a", "b");
    }
}
