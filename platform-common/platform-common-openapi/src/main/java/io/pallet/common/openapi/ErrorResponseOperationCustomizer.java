package io.pallet.common.openapi;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.Map;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.web.method.HandlerMethod;

/**
 * Adds the platform's standard error responses ({@code ErrorResponse},
 * {@code platform-common-exception}) to every operation, unless the operation already documents
 * that status code itself.
 */
public class ErrorResponseOperationCustomizer implements OperationCustomizer {

    private static final Map<String, String> STANDARD_RESPONSES = Map.of(
            "400", "Validation or malformed-request failure",
            "401", "Missing or invalid credentials",
            "403", "Authenticated but not authorized for this resource",
            "404", "Resource not found",
            "409", "Conflict with current resource state",
            "429", "Rate limit exceeded",
            "500", "Unexpected server error");

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        ApiResponses responses = operation.getResponses();
        STANDARD_RESPONSES.forEach((status, description) -> {
            if (!responses.containsKey(status)) {
                responses.addApiResponse(status, errorResponse(description));
            }
        });
        return operation;
    }

    private ApiResponse errorResponse(String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content()
                        .addMediaType(
                                "application/json",
                                new MediaType().schema(new Schema<>().$ref("#/components/schemas/ErrorResponse"))));
    }
}
