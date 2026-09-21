package io.pallet.orgteam.member;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

public final class MemberExceptions {

    private MemberExceptions() {}

    public static class MemberNotFoundException extends AppException {

        public MemberNotFoundException() {
            super(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Member not found.", "No active member with that id");
        }
    }

    public static class InvalidSortException extends AppException {

        public InvalidSortException(String property) {
            super(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_SORT",
                    "Unsupported sort field.",
                    "Sort property not allowed: " + property);
        }
    }
}
