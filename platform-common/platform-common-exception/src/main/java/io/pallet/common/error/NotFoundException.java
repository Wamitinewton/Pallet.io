package io.pallet.common.error;

import org.springframework.http.HttpStatus;

public class NotFoundException extends AppException {

    public NotFoundException(String clientMessage) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", clientMessage, clientMessage);
    }

    public NotFoundException(String resource, Object identifier) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "%s not found".formatted(resource),
            "%s not found: %s".formatted(resource, identifier));
    }
}
