package io.pallet.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

class PageResponseTest {

    @Test
    void ofCopiesEveryFieldOffThePage() {
        Page<String> page = new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 2, Sort.by("x")), 7);

        PageResponse<String> response = PageResponse.of(page);

        assertThat(response.content()).containsExactly("a", "b");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(7);
        assertThat(response.totalPages()).isEqualTo(4);
        assertThat(response.first()).isFalse();
        assertThat(response.last()).isFalse();
    }

    @Test
    void ofWithMapperTransformsContentAndKeepsMetadata() {
        Page<String> page = new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 2, Sort.by("x")), 7);

        PageResponse<Integer> response = PageResponse.of(page, String::length);

        assertThat(response.content()).containsExactly(1, 1);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(7);
        assertThat(response.totalPages()).isEqualTo(4);
    }

    @Test
    void emptyPageReportsFirstAndLast() {
        PageResponse<String> response = PageResponse.of(Page.empty());

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.first()).isTrue();
        assertThat(response.last()).isTrue();
    }
}
