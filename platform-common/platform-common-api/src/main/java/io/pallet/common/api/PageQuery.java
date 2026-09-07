package io.pallet.common.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Inbound pagination and sort parameters, bound from query params. Centralises
 * page/size clamping and the default-sort fallback so no controller repeats it.
 *
 * <p>{@code sort} grammar: {@code field}, {@code field,asc} or {@code field,desc},
 * with {@code ;} separating clauses for multi-column sort. A malformed string
 * falls back to the supplied default sort rather than throwing — an unknown
 * property name is left for Spring Data to reject downstream.
 */
public record PageQuery(Integer page, Integer size, String sort) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public Pageable toPageable(Sort defaultSort) {
        Objects.requireNonNull(defaultSort, "defaultSort is required; pagination must be deterministic");
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return PageRequest.of(p, s, parseSort().orElse(defaultSort));
    }

    private Optional<Sort> parseSort() {
        if (sort == null || sort.isBlank()) {
            return Optional.empty();
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (String clause : sort.split(";")) {
            String[] parts = clause.trim().split(",");
            String property = parts[0].trim();
            if (property.isEmpty() || parts.length > 2) {
                return Optional.empty();
            }
            Sort.Direction direction = Sort.DEFAULT_DIRECTION;
            if (parts.length == 2) {
                Optional<Sort.Direction> parsed = Sort.Direction.fromOptionalString(parts[1].trim());
                if (parsed.isEmpty()) {
                    return Optional.empty();
                }
                direction = parsed.get();
            }
            orders.add(new Sort.Order(direction, property));
        }
        return orders.isEmpty() ? Optional.empty() : Optional.of(Sort.by(orders));
    }
}
