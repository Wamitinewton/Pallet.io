package io.pallet.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The failure-side counterpart of {@code io.pallet.common.api.ApiResponse}. Every
 * error thrown by a Pallet HTTP endpoint is rendered in this shape by
 * {@link GlobalExceptionHandler}.
 *
 * <p>{@code timestamp} is a UTC {@link Instant} — Pallet standardises on {@code Instant}
 * everywhere. {@code validationErrors} carries the same array shape whether the failure
 * was bean validation or a business rule, so a client parses one structure regardless.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        boolean success,
        String message,
        String error,
        int statusCode,
        Instant timestamp,
        String path,
        List<ValidationError> validationErrors,
        Map<String, Object> meta) {

    public static ErrorResponse of(HttpStatus status, String message, String errorCode, String path) {
        return new ErrorResponse(false, message, errorCode, status.value(), Instant.now(), path, null, null);
    }

    public ErrorResponse withValidationErrors(List<ValidationError> errors) {
        return new ErrorResponse(success, message, error, statusCode, timestamp, path, errors, meta);
    }

    public ErrorResponse withMeta(Map<String, Object> meta) {
        return new ErrorResponse(success, message, error, statusCode, timestamp, path, validationErrors, meta);
    }

    public record ValidationError(String field, String message) {}
}
