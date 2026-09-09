package io.pallet.common.test.assertions;

import static io.pallet.common.test.assertions.PalletAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.api.ApiResponse;
import org.junit.jupiter.api.Test;

class ApiResponseAssertTest {

    @Test
    void isSuccessPassesSilentlyOnASuccessfulResponse() {
        assertThat(ApiResponse.ok("created", "abc")).isSuccess();
    }

    @Test
    void isSuccessFailsWithTheResponseMessageWhenNotSuccessful() {
        ApiResponse<String> failed = new ApiResponse<>(false, "boom", null);

        assertThatThrownBy(() -> assertThat(failed).isSuccess())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("boom");
    }

    @Test
    void hasMessagePassesSilentlyWhenTheMessageMatches() {
        assertThat(ApiResponse.ok("created", "abc")).hasMessage("created");
    }

    @Test
    void hasMessageFailsWithTheActualMessageWhenItDiffers() {
        ApiResponse<String> response = ApiResponse.ok("created", "abc");

        assertThatThrownBy(() -> assertThat(response).hasMessage("wrong"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("created");
    }

    @Test
    void dataHandsBackAnObjectAssertOverThePayload() {
        assertThat(ApiResponse.ok("created", "abc")).data().isEqualTo("abc");
    }

    @Test
    void dataFailsWhenTheResponseIsNotSuccessful() {
        ApiResponse<String> failed = new ApiResponse<>(false, "boom", null);

        assertThatThrownBy(() -> assertThat(failed).data())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("boom");
    }
}
