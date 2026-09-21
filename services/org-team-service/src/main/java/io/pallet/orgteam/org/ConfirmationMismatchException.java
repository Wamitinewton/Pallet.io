package io.pallet.orgteam.org;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

public class ConfirmationMismatchException extends AppException {

    public ConfirmationMismatchException() {
        super(
                HttpStatus.BAD_REQUEST,
                "CONFIRMATION_MISMATCH",
                "Confirm the deletion by sending the organization's slug in the X-Confirm-Slug header.",
                "X-Confirm-Slug is missing or does not match the organization's slug");
    }
}
