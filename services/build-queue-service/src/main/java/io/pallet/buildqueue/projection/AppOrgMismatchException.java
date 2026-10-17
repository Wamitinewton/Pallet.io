package io.pallet.buildqueue.projection;

import io.pallet.common.inbox.NonRetryableEventException;

/** An app announced under a second org. Applying it would move another tenant's builds, so it is dead-lettered. */
public class AppOrgMismatchException extends NonRetryableEventException {

    public AppOrgMismatchException(String event) {
        super(event + ".appId already belongs to another org");
    }
}
