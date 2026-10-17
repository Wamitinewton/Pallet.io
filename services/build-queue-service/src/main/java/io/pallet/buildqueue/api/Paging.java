package io.pallet.buildqueue.api;

import io.pallet.common.api.PageQuery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Every list here has one fixed order, so a {@code sort} parameter is ignored; page and size are clamped. */
final class Paging {

    private Paging() {}

    static Pageable of(PageQuery query) {
        return new PageQuery(query.page(), query.size(), null).toPageable(Sort.unsorted());
    }
}
