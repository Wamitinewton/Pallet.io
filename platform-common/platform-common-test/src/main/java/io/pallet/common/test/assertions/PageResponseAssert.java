package io.pallet.common.test.assertions;

import io.pallet.common.api.PageResponse;
import org.assertj.core.api.AbstractObjectAssert;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.ListAssert;

/** AssertJ assertions over {@link PageResponse}, the pagination envelope every endpoint returns. */
public final class PageResponseAssert<T> extends AbstractObjectAssert<PageResponseAssert<T>, PageResponse<T>> {

    PageResponseAssert(PageResponse<T> actual) {
        super(actual, PageResponseAssert.class);
    }

    public PageResponseAssert<T> hasTotalElements(long expected) {
        isNotNull();
        Assertions.assertThat(actual.totalElements()).isEqualTo(expected);
        return this;
    }

    public PageResponseAssert<T> isFirstPage() {
        isNotNull();
        if (!actual.first()) {
            failWithMessage("Expected PageResponse to be the first page but it was not");
        }
        return this;
    }

    public PageResponseAssert<T> isLastPage() {
        isNotNull();
        if (!actual.last()) {
            failWithMessage("Expected PageResponse to be the last page but it was not");
        }
        return this;
    }

    public ListAssert<T> content() {
        isNotNull();
        return Assertions.assertThat(actual.content());
    }
}
