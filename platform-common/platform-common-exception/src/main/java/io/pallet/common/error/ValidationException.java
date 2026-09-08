package io.pallet.common.error;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Multi-reason business validation failure; the reasons travel on {@code validationErrors}.
 */
public class ValidationException extends AppException {

    public ValidationException(String clientMessage, List<String> reasons) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_ERROR", clientMessage, clientMessage);
        withValidationErrors(reasons);
    }
}
