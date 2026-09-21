package io.pallet.orgteam.org;

import io.pallet.orgteam.inbox.NonRetryableEventException;

class OrgSlugConflictException extends NonRetryableEventException {

    OrgSlugConflictException(String orgId, Throwable cause) {
        super("Slug of organization " + orgId + " is already claimed by another organization", cause);
    }
}
