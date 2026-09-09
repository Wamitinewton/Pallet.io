package io.pallet.common.test.assertions;

import io.pallet.common.api.ApiResponse;
import io.pallet.common.api.PageResponse;
import io.pallet.common.error.ErrorResponse;

/**
 * Entry point for this module's custom AssertJ assertions. A test statically imports
 * {@code assertThat} from here alongside AssertJ's own {@code Assertions.assertThat}; overload
 * resolution picks the right one by the static type of the argument.
 */
public final class PalletAssertions {

    private PalletAssertions() {}

    public static <T> ApiResponseAssert<T> assertThat(ApiResponse<T> actual) {
        return new ApiResponseAssert<>(actual);
    }

    public static ErrorResponseAssert assertThat(ErrorResponse actual) {
        return new ErrorResponseAssert(actual);
    }

    public static <T> PageResponseAssert<T> assertThat(PageResponse<T> actual) {
        return new PageResponseAssert<>(actual);
    }
}
