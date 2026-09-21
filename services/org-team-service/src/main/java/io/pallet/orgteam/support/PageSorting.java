package io.pallet.orgteam.support;

import io.pallet.common.api.PageQuery;
import java.util.function.UnaryOperator;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public final class PageSorting {

    private PageSorting() {}

    /**
     * Applies {@code defaultSort} when the caller did not choose one; otherwise maps each requested
     * order through {@code whitelist} (which throws for an unlisted property) and appends the tiebreaker.
     */
    public static Pageable resolve(
            PageQuery pageQuery, Sort defaultSort, Sort.Order tiebreaker, UnaryOperator<Sort.Order> whitelist) {
        Pageable requested = pageQuery.toPageable(defaultSort);
        Sort sort = requested.getSort();
        if (!sort.equals(defaultSort)) {
            sort = Sort.by(sort.stream().map(whitelist).toList()).and(Sort.by(tiebreaker));
        }
        return PageRequest.of(requested.getPageNumber(), requested.getPageSize(), sort);
    }
}
