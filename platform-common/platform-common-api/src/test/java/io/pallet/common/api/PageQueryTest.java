package io.pallet.common.api;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageQueryTest {

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    @Test
    void nullsFallBackToDefaultPageAndSize() {
        Pageable pageable = new PageQuery(null, null, null).toPageable(DEFAULT_SORT);

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(PageQuery.DEFAULT_SIZE);
    }

    @Test
    void sizeIsClampedToMax() {
        assertThat(new PageQuery(0, 100_000, null).toPageable(DEFAULT_SORT).getPageSize())
            .isEqualTo(PageQuery.MAX_SIZE);
    }

    @Test
    void negativePageBecomesZero() {
        assertThat(new PageQuery(-3, null, null).toPageable(DEFAULT_SORT).getPageNumber()).isZero();
    }

    @Test
    void zeroSizeBecomesDefault() {
        assertThat(new PageQuery(0, 0, null).toPageable(DEFAULT_SORT).getPageSize())
            .isEqualTo(PageQuery.DEFAULT_SIZE);
    }

    @Test
    void bareFieldSortsAscending() {
        Sort sort = new PageQuery(0, 20, "name").toPageable(DEFAULT_SORT).getSort();

        assertThat(sort.getOrderFor("name")).isNotNull();
        assertThat(sort.getOrderFor("name").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void fieldWithDirectionIsHonoured() {
        Sort sort = new PageQuery(0, 20, "name,desc").toPageable(DEFAULT_SORT).getSort();

        assertThat(sort.getOrderFor("name").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void multipleClausesProduceMultipleOrders() {
        Sort sort = new PageQuery(0, 20, "a,desc;b,asc").toPageable(DEFAULT_SORT).getSort();

        assertThat(sort.getOrderFor("a").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(sort.getOrderFor("b").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void malformedSortFallsBackToDefault() {
        assertThat(new PageQuery(0, 20, "").toPageable(DEFAULT_SORT).getSort()).isEqualTo(DEFAULT_SORT);
        assertThat(new PageQuery(0, 20, "garbage,sideways").toPageable(DEFAULT_SORT).getSort())
            .isEqualTo(DEFAULT_SORT);
    }

    @Test
    void missingDefaultSortFailsFast() {
        assertThatThrownBy(() -> new PageQuery(0, 20, null).toPageable(null))
            .isInstanceOf(NullPointerException.class);
    }
}
