package io.pallet.common.error;

import org.springframework.http.HttpStatus;

/** Client-input fault not caught by bean validation. */
public class BadRequestException extends AppException {

    public BadRequestException(String clientMessage) {
        super(HttpStatus.BAD_REQUEST, "BAD_REQUEST", clientMessage, clientMessage);
    }

    public BadRequestException(String clientMessage, String technicalMessage) {
        super(HttpStatus.BAD_REQUEST, "BAD_REQUEST", clientMessage, technicalMessage);
    }
}
