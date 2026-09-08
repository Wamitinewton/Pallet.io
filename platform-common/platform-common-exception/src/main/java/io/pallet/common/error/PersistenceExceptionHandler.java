package io.pallet.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps Spring's data-access exceptions to the {@link ErrorResponse} shape. Registered only
 * when {@code spring-tx} is on the classpath (see {@link PalletErrorHandlingAutoConfiguration}).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PersistenceExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PersistenceExceptionHandler.class);

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLocking(
            OptimisticLockingFailureException ex, HttpServletRequest request) {
        log.warn("[CONCURRENT_MODIFICATION] {} {}", request.getMethod(), request.getRequestURI());
        return HandlerSupport.render(
                HttpStatus.CONFLICT,
                "This resource was changed by someone else. Please retry.",
                "CONCURRENT_MODIFICATION",
                request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn(
                "[DATA_INTEGRITY_VIOLATION] {} {} — {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return HandlerSupport.render(
                HttpStatus.CONFLICT, "This request conflicts with existing data.", "DATA_INTEGRITY_VIOLATION", request);
    }
}
