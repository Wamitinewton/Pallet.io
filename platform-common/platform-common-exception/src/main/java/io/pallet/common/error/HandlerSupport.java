package io.pallet.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Shared rendering for the exception advice classes in this package.
 */
final class HandlerSupport {

    private HandlerSupport() {}

    static ResponseEntity<ErrorResponse> render(
            HttpStatus status, String message, String errorCode, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status, message, errorCode, request.getRequestURI()));
    }
}
