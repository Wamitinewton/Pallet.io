package io.pallet.common.error;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps Spring Security's own exceptions to the {@link ErrorResponse} shape. Registered only
 * when Spring Security is on the classpath (see {@link PalletErrorHandlingAutoConfiguration}).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SecurityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("[ACCESS_DENIED] {} {}", request.getMethod(), request.getRequestURI());
        return HandlerSupport.render(HttpStatus.FORBIDDEN, "You do not have permission to perform this action.",
                "ACCESS_DENIED", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        log.warn("[AUTHENTICATION_REQUIRED] {} {}", request.getMethod(), request.getRequestURI());
        return HandlerSupport.render(HttpStatus.UNAUTHORIZED, "Authentication is required to access this resource.",
                "AUTHENTICATION_REQUIRED", request);
    }
}
