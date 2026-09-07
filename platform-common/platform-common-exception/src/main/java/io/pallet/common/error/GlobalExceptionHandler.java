package io.pallet.common.error;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import io.pallet.common.error.ErrorResponse.ValidationError;

/**
 * Renders every {@link AppException} and the common Spring MVC exceptions in the
 * {@link ErrorResponse} shape. Registered by {@link PalletErrorHandlingAutoConfiguration};
 * a service that needs extra handlers subclasses or replaces this bean.
 *
 * <p>Lowest precedence so the classpath-conditional advices ({@code SecurityExceptionHandler},
 * {@code PersistenceExceptionHandler}) win for the exceptions they own before the
 * {@link Exception} fallback here catches them.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String FALLBACK_MESSAGE = "An unexpected error occurred. Please try again later.";

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleAppException(AppException ex, HttpServletRequest request) {
        if (ex.getStatus().is5xxServerError()) {
            log.error("[{}] {} {} — {}", ex.getErrorCode(), request.getMethod(), request.getRequestURI(),
                    ex.getMessage(), ex);
        } else {
            log.warn("[{}] {} {} — {}", ex.getErrorCode(), request.getMethod(), request.getRequestURI(), ex.getMessage());
        }
        ErrorResponse body = ErrorResponse.of(ex.getStatus(), ex.getClientMessage(), ex.getErrorCode(),
                request.getRequestURI());
        if (ex.getValidationErrors() != null) {
            body = body.withValidationErrors(ex.getValidationErrors().stream()
                    .map(reason -> new ValidationError(null, reason))
                    .toList());
        }
        if (ex.getMeta() != null) {
            body = body.withMeta(ex.getMeta());
        }
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ValidationError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new ValidationError(e.getField(), e.getDefaultMessage()))
                .toList();
        log.warn("[VALIDATION_ERROR] {} {} — {} field error(s)", request.getMethod(), request.getRequestURI(),
                fieldErrors.size());
        return ResponseEntity.badRequest().body(
                ErrorResponse.of(HttpStatus.BAD_REQUEST, "Validation failed.", "VALIDATION_ERROR", request.getRequestURI())
                        .withValidationErrors(fieldErrors));
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    public ResponseEntity<ErrorResponse> handleParameterValidation(Exception ex, HttpServletRequest request) {
        log.warn("[VALIDATION_ERROR] {} {} — {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return HandlerSupport.render(HttpStatus.BAD_REQUEST, "Validation failed.", "VALIDATION_ERROR", request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        return HandlerSupport.render(HttpStatus.BAD_REQUEST,
                "Required request header '" + ex.getHeaderName() + "' is missing.", "MISSING_REQUEST_HEADER", request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        return HandlerSupport.render(HttpStatus.BAD_REQUEST,
                "Required request parameter '" + ex.getParameterName() + "' is missing.", "MISSING_REQUEST_PARAMETER",
                request);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(
            MissingServletRequestPartException ex, HttpServletRequest request) {
        return HandlerSupport.render(HttpStatus.BAD_REQUEST,
                "Required request part '" + ex.getRequestPartName() + "' is missing.", "MISSING_REQUEST_PART", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        return HandlerSupport.render(HttpStatus.BAD_REQUEST, "Request body is missing or malformed.",
                "MALFORMED_REQUEST_BODY", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return HandlerSupport.render(HttpStatus.BAD_REQUEST,
                "Parameter '" + ex.getName() + "' has the wrong type.", "INVALID_PARAMETER_TYPE", request);
    }

    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ErrorResponse> handlePropertyReference(
            PropertyReferenceException ex, HttpServletRequest request) {
        return HandlerSupport.render(HttpStatus.BAD_REQUEST,
                "Unknown sort or filter property: '" + ex.getPropertyName() + "'.", "INVALID_QUERY_PROPERTY", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return HandlerSupport.render(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not allowed for this resource.",
                "METHOD_NOT_ALLOWED", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return HandlerSupport.render(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type.",
                "UNSUPPORTED_MEDIA_TYPE", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
            NoResourceFoundException ex, HttpServletRequest request) {
        return HandlerSupport.render(HttpStatus.NOT_FOUND, "The requested resource does not exist.", "ROUTE_NOT_FOUND",
                request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(
            MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return HandlerSupport.render(HttpStatus.PAYLOAD_TOO_LARGE, "The uploaded file is too large.", "PAYLOAD_TOO_LARGE",
                request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("[INTERNAL_ERROR] {} {}", request.getMethod(), request.getRequestURI(), ex);
        return HandlerSupport.render(HttpStatus.INTERNAL_SERVER_ERROR, FALLBACK_MESSAGE, "INTERNAL_ERROR", request);
    }
}
