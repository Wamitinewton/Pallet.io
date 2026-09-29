package io.pallet.gitintegration.security;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

public final class AccessExceptions {

    private AccessExceptions() {}

    /** One response for "no such org", "not yours", and "deleted", so none of them can be told apart (ADR-0018). */
    public static class OrgNotFoundException extends AppException {

        public OrgNotFoundException() {
            super(HttpStatus.NOT_FOUND, "ORG_NOT_FOUND", "Organization not found.", "Organization not found");
        }
    }

    public static class NotAMemberException extends AppException {

        public NotAMemberException() {
            super(
                    HttpStatus.FORBIDDEN,
                    "NOT_A_MEMBER",
                    "You are not a member of this organization.",
                    "Caller has no active membership in the organization");
        }
    }

    public static class InsufficientRoleException extends AppException {

        public InsufficientRoleException() {
            super(
                    HttpStatus.FORBIDDEN,
                    "INSUFFICIENT_ROLE",
                    "Your role does not permit this action.",
                    "Caller's role is below what the operation requires");
        }
    }

    public static class AppNotFoundException extends AppException {

        public AppNotFoundException() {
            super(HttpStatus.NOT_FOUND, "APP_NOT_FOUND", "App not found.", "App is not an active app of this org");
        }
    }

    public static class InstallationNotFoundException extends AppException {

        public InstallationNotFoundException() {
            super(
                    HttpStatus.NOT_FOUND,
                    "INSTALLATION_NOT_FOUND",
                    "Installation not found.",
                    "Installation has no active link to this org");
        }
    }

    /** One response for every way a state can fail, so a caller learns nothing about which check caught it. */
    public static class InvalidAuthorizationStateException extends AppException {

        public InvalidAuthorizationStateException() {
            super(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_AUTHORIZATION_STATE",
                    "The GitHub authorization could not be completed. Start again.",
                    "Authorization state rejected");
        }
    }
}
