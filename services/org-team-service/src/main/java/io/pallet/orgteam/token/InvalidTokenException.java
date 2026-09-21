package io.pallet.orgteam.token;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

public class InvalidTokenException extends AppException {

    private static final String CLIENT_MESSAGE = "This link is invalid or has expired.";

    public InvalidTokenException(String technicalMessage) {
        super(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", CLIENT_MESSAGE, technicalMessage);
    }

    public InvalidTokenException(String technicalMessage, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", CLIENT_MESSAGE, technicalMessage, cause);
    }
}
