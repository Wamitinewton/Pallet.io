package io.pallet.orgteam.org;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

public final class OrgExceptions {

    private OrgExceptions() {}

    public static class PersonalOrgImmutableException extends AppException {

        public PersonalOrgImmutableException() {
            super(
                    HttpStatus.CONFLICT,
                    "PERSONAL_ORG_IMMUTABLE",
                    "A personal organization cannot be deleted or shared.",
                    "Operation applies only to TEAM organizations");
        }
    }

    public static class OrgSlugTakenException extends AppException {

        public OrgSlugTakenException() {
            super(
                    HttpStatus.CONFLICT,
                    "SLUG_TAKEN",
                    "That slug is already in use by another organization.",
                    "Organization slug is taken");
        }
    }

    public static class EmailClaimMissingException extends AppException {

        public EmailClaimMissingException() {
            super(
                    HttpStatus.FORBIDDEN,
                    "EMAIL_CLAIM_MISSING",
                    "Your session does not carry a usable email address. Sign in again.",
                    "Token has no valid email claim");
        }
    }
}
