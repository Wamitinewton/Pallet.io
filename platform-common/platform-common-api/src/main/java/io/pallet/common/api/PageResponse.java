package io.pallet.common.api;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Pagination envelope. Controllers must wrap repository {@link Page} results in
 * this shape rather than returning a raw Spring {@code Page}, whose JSON is
 * unstable across versions and leaks {@code pageable}/{@code sort} internals.
 */
public record PageResponse<T>(
        List<T> content, int page, int size, long totalElements, int totalPages, boolean first, boolean last) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }

    public static <S, T> PageResponse<T> of(Page<S> page, Function<? super S, ? extends T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().<T>map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
