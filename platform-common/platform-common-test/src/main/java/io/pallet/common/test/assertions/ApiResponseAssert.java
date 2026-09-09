package io.pallet.common.test.assertions;

import io.pallet.common.api.ApiResponse;
import org.assertj.core.api.AbstractObjectAssert;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.ObjectAssert;

/** AssertJ assertions over {@link ApiResponse}, the success envelope every endpoint returns. */
public final class ApiResponseAssert<T> extends AbstractObjectAssert<ApiResponseAssert<T>, ApiResponse<T>> {

    ApiResponseAssert(ApiResponse<T> actual) {
        super(actual, ApiResponseAssert.class);
    }

    public ApiResponseAssert<T> isSuccess() {
        isNotNull();
        if (!actual.success()) {
            failWithMessage("Expected ApiResponse to be successful but it was not; message was <%s>", actual.message());
        }
        return this;
    }

    public ApiResponseAssert<T> hasMessage(String expected) {
        isNotNull();
        Assertions.assertThat(actual.message()).isEqualTo(expected);
        return this;
    }

    public ObjectAssert<T> data() {
        isSuccess();
        return Assertions.assertThat(actual.data());
    }
}
