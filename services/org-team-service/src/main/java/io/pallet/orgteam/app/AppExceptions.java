package io.pallet.orgteam.app;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

public final class AppExceptions {

    private AppExceptions() {}

    public static class AppNotFoundException extends AppException {

        public AppNotFoundException() {
            super(HttpStatus.NOT_FOUND, "APP_NOT_FOUND", "App not found.", "No such app in the organization");
        }
    }

    public static class InvalidRegionException extends AppException {

        public InvalidRegionException(CloudProvider provider) {
            super(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REGION",
                    "That region is not available for " + provider + ".",
                    "Region is not on the allow-list for " + provider);
        }
    }
}
