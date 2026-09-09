package io.pallet.common.test.assertions;

import io.pallet.common.error.ErrorResponse;
import org.assertj.core.api.AbstractObjectAssert;
import org.assertj.core.api.Assertions;
import org.springframework.http.HttpStatus;

/** AssertJ assertions over {@link ErrorResponse}, the failure envelope every endpoint returns. */
public final class ErrorResponseAssert extends AbstractObjectAssert<ErrorResponseAssert, ErrorResponse> {

    ErrorResponseAssert(ErrorResponse actual) {
        super(actual, ErrorResponseAssert.class);
    }

    public ErrorResponseAssert isFailure() {
        isNotNull();
        if (actual.success()) {
            failWithMessage("Expected ErrorResponse to represent a failure but success was true");
        }
        return this;
    }

    public ErrorResponseAssert hasErrorCode(String expected) {
        isNotNull();
        Assertions.assertThat(actual.error()).isEqualTo(expected);
        return this;
    }

    public ErrorResponseAssert hasStatusCode(int expected) {
        isNotNull();
        Assertions.assertThat(actual.statusCode()).isEqualTo(expected);
        return this;
    }

    public ErrorResponseAssert hasStatus(HttpStatus expected) {
        return hasStatusCode(expected.value());
    }

    public ErrorResponseAssert hasMessage(String expected) {
        isNotNull();
        Assertions.assertThat(actual.message()).isEqualTo(expected);
        return this;
    }
}
