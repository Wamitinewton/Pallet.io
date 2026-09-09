package io.pallet.common.test.assertions;

import static io.pallet.common.test.assertions.PalletAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.error.ErrorResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorResponseAssertTest {

    private static ErrorResponse notFound() {
        return new ErrorResponse(false, "not found", "NOT_FOUND", 404, Instant.now(), "/widgets/1", null, null);
    }

    @Test
    void isFailurePassesSilentlyWhenSuccessIsFalse() {
        assertThat(notFound()).isFailure();
    }

    @Test
    void isFailureFailsWhenSuccessIsTrue() {
        ErrorResponse notActuallyAFailure =
                new ErrorResponse(true, "ok", null, 200, Instant.now(), "/widgets/1", null, null);

        assertThatThrownBy(() -> assertThat(notActuallyAFailure).isFailure())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("failure");
    }

    @Test
    void hasErrorCodePassesSilentlyWhenTheCodeMatches() {
        assertThat(notFound()).hasErrorCode("NOT_FOUND");
    }

    @Test
    void hasErrorCodeFailsWithTheActualCodeWhenItDiffers() {
        ErrorResponse response = notFound();

        assertThatThrownBy(() -> assertThat(response).hasErrorCode("WRONG"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("NOT_FOUND");
    }

    @Test
    void hasStatusCodePassesSilentlyWhenTheCodeMatches() {
        assertThat(notFound()).hasStatusCode(404);
    }

    @Test
    void hasStatusComparesAgainstTheHttpStatusValue() {
        assertThat(notFound()).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void hasStatusFailsWithTheActualStatusCodeWhenItDiffers() {
        ErrorResponse response = notFound();

        assertThatThrownBy(() -> assertThat(response).hasStatus(HttpStatus.CONFLICT))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("404");
    }

    @Test
    void hasMessagePassesSilentlyWhenTheMessageMatches() {
        assertThat(notFound()).hasMessage("not found");
    }

    @Test
    void hasMessageFailsWithTheActualMessageWhenItDiffers() {
        ErrorResponse response = notFound();

        assertThatThrownBy(() -> assertThat(response).hasMessage("wrong"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("not found");
    }
}
