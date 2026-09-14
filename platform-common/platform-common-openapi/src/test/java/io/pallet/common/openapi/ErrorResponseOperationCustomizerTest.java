package io.pallet.common.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

class ErrorResponseOperationCustomizerTest {

    private final ErrorResponseOperationCustomizer customizer = new ErrorResponseOperationCustomizer();

    @Test
    void addsEveryStandardResponseToAnOperationWithNone() {
        Operation operation = new Operation().responses(new ApiResponses());

        Operation customized = customizer.customize(operation, null);

        ApiResponses responses = customized.getResponses();
        assertThat(responses.keySet()).containsExactlyInAnyOrder("400", "401", "403", "404", "409", "429", "500");
        responses
                .values()
                .forEach(response -> assertThat(response.getContent()
                                .get("application/json")
                                .getSchema()
                                .get$ref())
                        .isEqualTo("#/components/schemas/ErrorResponse"));
    }

    @Test
    void keepsAnAlreadyDocumentedResponseUnmodified() {
        ApiResponse explicitNotFound = new ApiResponse().description("custom not-found");
        Operation operation = new Operation().responses(new ApiResponses().addApiResponse("404", explicitNotFound));

        Operation customized = customizer.customize(operation, null);

        assertThat(customized.getResponses().get("404")).isSameAs(explicitNotFound);
    }
}
