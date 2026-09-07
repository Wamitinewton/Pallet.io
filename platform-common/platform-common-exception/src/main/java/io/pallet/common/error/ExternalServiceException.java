package io.pallet.common.error;

import org.springframework.http.HttpStatus;

/**
 * An outbound call to a third party failed past its retries or its circuit breaker is open.
 * Thrown by {@code platform-common-resilience}; no other module should define its own.
 */
public class ExternalServiceException extends AppException {

    public ExternalServiceException(String clientMessage, String technicalMessage, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "EXTERNAL_SERVICE_ERROR", clientMessage, technicalMessage, cause);
    }
}
