package io.pallet.common.api;

/**
 * Success envelope for every Pallet HTTP endpoint. The HTTP status lives on the
 * surrounding {@code ResponseEntity}; this record never carries a status code.
 *
 * <p>There is no failure factory by design — errors are rendered from
 * {@code ErrorResponse} by the global exception handler, never hand-built here.
 * A {@code null} {@code data} with {@code success=true} is a valid shape (e.g. a
 * 200 after a delete), so the key is never hidden.
 */
public record ApiResponse<T>(boolean success, String message, T data) {

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    public static <T> ApiResponse<T> ok(String message) {
        return new ApiResponse<>(true, message, null);
    }
}
