package io.pallet.orgteam.security;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

public final class AccessExceptions {

    private AccessExceptions() {}

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

    public static class ReauthenticationRequiredException extends AppException {

        public ReauthenticationRequiredException() {
            super(
                    HttpStatus.FORBIDDEN,
                    "REAUTHENTICATION_REQUIRED",
                    "Sign in again to confirm this action.",
                    "auth_time is missing or older than the recent-authentication window");
        }
    }

    public static class InvalidRoleTransitionException extends AppException {

        public InvalidRoleTransitionException() {
            super(
                    HttpStatus.CONFLICT,
                    "INVALID_ROLE_TRANSITION",
                    "That role change is not allowed.",
                    "Role transition violates the membership policy");
        }
    }

    public static class LastOwnerException extends AppException {

        public LastOwnerException() {
            super(
                    HttpStatus.CONFLICT,
                    "LAST_OWNER",
                    "The organization owner cannot be removed. Transfer ownership first.",
                    "Operation would leave the organization without its owner");
        }
    }
}
