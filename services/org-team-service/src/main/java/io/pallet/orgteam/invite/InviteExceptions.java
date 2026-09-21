package io.pallet.orgteam.invite;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

public final class InviteExceptions {

    private InviteExceptions() {}

    public static class AlreadyAMemberException extends AppException {

        public AlreadyAMemberException() {
            super(
                    HttpStatus.CONFLICT,
                    "ALREADY_A_MEMBER",
                    "That person is already a member of this organization.",
                    "An active member with that email exists");
        }
    }

    public static class MemberPreviouslyRemovedException extends AppException {

        public MemberPreviouslyRemovedException() {
            super(
                    HttpStatus.CONFLICT,
                    "MEMBER_PREVIOUSLY_REMOVED",
                    "That person was removed from this organization and cannot be invited again.",
                    "A removed member with that email exists");
        }
    }

    public static class InviteAlreadyPendingException extends AppException {

        public InviteAlreadyPendingException() {
            super(
                    HttpStatus.CONFLICT,
                    "INVITE_ALREADY_PENDING",
                    "An invitation to that email is already pending.",
                    "A pending invite for that email exists");
        }
    }

    public static class QuotaExceededException extends AppException {

        public QuotaExceededException(String clientMessage) {
            super(HttpStatus.CONFLICT, "QUOTA_EXCEEDED", clientMessage, clientMessage);
        }
    }

    public static class InviteNotFoundException extends AppException {

        public InviteNotFoundException() {
            super(HttpStatus.NOT_FOUND, "INVITE_NOT_FOUND", "Invite not found.", "No such invite in the organization");
        }
    }

    public static class InviteNotPendingException extends AppException {

        public InviteNotPendingException() {
            super(
                    HttpStatus.CONFLICT,
                    "INVITE_NOT_PENDING",
                    "This invitation is no longer pending.",
                    "Invite is accepted, revoked or expired");
        }
    }
}
