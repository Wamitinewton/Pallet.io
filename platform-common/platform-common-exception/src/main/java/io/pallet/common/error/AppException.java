package io.pallet.common.error;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * Base type for every expected application error. Services model their own domain
 * errors as subclasses; the single {@code AppException} handler in
 * {@link GlobalExceptionHandler} renders all of them, so a new subclass needs no
 * handler change.
 *
 * <p>{@code errorCode} is a stable contract — clients branch on it, so renaming one
 * is a breaking API change. {@code clientMessage} is the response body;
 * {@code technicalMessage} (the {@link RuntimeException} message) is for logs only
 * and never reaches the client.
 */
public abstract class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final String clientMessage;
    private Map<String, Object> meta;
    private List<String> validationErrors;

    protected AppException(HttpStatus status, String errorCode, String clientMessage, String technicalMessage) {
        this(status, errorCode, clientMessage, technicalMessage, null);
    }

    protected AppException(HttpStatus status, String errorCode, String clientMessage,
                           String technicalMessage, Throwable cause) {
        super(technicalMessage, cause);
        this.status = status;
        this.errorCode = errorCode;
        this.clientMessage = clientMessage;
    }

    /**
     * Attach structured context, surfaced as {@code ErrorResponse.meta}. Set-once.
     */
    public AppException withMeta(Map<String, Object> meta) {
        if (this.meta == null && meta != null) {
            this.meta = Map.copyOf(meta);
        }
        return this;
    }

    /**
     * Attach failure reasons, surfaced as {@code ErrorResponse.validationErrors}. Set-once.
     */
    public AppException withValidationErrors(List<String> validationErrors) {
        if (this.validationErrors == null && validationErrors != null) {
            this.validationErrors = List.copyOf(validationErrors);
        }
        return this;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getClientMessage() {
        return clientMessage;
    }

    public Map<String, Object> getMeta() {
        return meta;
    }

    public List<String> getValidationErrors() {
        return validationErrors;
    }
}
