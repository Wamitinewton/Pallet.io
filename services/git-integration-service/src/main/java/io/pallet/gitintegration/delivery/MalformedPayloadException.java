package io.pallet.gitintegration.delivery;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

/**
 * A stored payload that fails validation. Retrying can't fix it, so the delivery is parked at once. The message names
 * the field and the rule, never the value.
 */
public class MalformedPayloadException extends AppException {

    public MalformedPayloadException(String field, String rule) {
        super(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "WEBHOOK_PAYLOAD_INVALID",
                "The webhook payload is invalid.",
                field + ": " + rule);
    }
}
