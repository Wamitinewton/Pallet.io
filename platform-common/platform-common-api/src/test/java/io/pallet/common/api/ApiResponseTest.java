package io.pallet.common.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void okWithDataCarriesPayload() {
        ApiResponse<String> response = ApiResponse.ok("created", "abc");

        assertThat(response.success()).isTrue();
        assertThat(response.message()).isEqualTo("created");
        assertThat(response.data()).isEqualTo("abc");
    }

    @Test
    void okWithoutDataLeavesDataNull() {
        ApiResponse<String> response = ApiResponse.ok("deleted");

        assertThat(response.success()).isTrue();
        assertThat(response.message()).isEqualTo("deleted");
        assertThat(response.data()).isNull();
    }

    @Test
    void serializesNullDataKeyRatherThanHidingIt() {
        String body = json.writeValueAsString(ApiResponse.ok("deleted"));

        assertThat(body).isEqualTo("{\"success\":true,\"message\":\"deleted\",\"data\":null}");
    }
}
