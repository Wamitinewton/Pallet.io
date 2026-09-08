package io.pallet.common.error;

import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.Map;

/**
 * Rate-limit or lockout; an optional {@code retryAfter} (seconds) is carried on {@code meta}.
 */
public class TooManyRequestsException extends AppException {

    public TooManyRequestsException(String clientMessage) {
        super(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", clientMessage, clientMessage);
    }

    public TooManyRequestsException(String clientMessage, Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", clientMessage, clientMessage);
        withMeta(Map.of("retryAfter", retryAfter.toSeconds()));
    }
}
