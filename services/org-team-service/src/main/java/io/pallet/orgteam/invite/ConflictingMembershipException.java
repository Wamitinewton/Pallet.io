package io.pallet.orgteam.invite;

import io.pallet.orgteam.inbox.NonRetryableEventException;

class ConflictingMembershipException extends NonRetryableEventException {

    ConflictingMembershipException(String orgId, String inviteId) {
        super("Invite " + inviteId + " in organization " + orgId + " names an email held by another active member");
    }
}
