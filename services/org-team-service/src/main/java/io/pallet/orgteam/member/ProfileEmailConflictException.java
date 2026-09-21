package io.pallet.orgteam.member;

import io.pallet.orgteam.inbox.NonRetryableEventException;

class ProfileEmailConflictException extends NonRetryableEventException {

    ProfileEmailConflictException(String orgId, String userId) {
        super("Profile update for user " + userId + " in organization " + orgId
                + " names an email held by another active member");
    }
}
