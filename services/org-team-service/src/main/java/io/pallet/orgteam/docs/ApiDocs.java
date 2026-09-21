package io.pallet.orgteam.docs;

public final class ApiDocs {

    private static final String BASIS = ", judged from your current membership, not your token's roles.";

    public static final String ANY_MEMBER = "Requires any active member of the organization" + BASIS;
    public static final String DEVELOPER = "Requires DEVELOPER or above" + BASIS;
    public static final String ADMIN = "Requires ADMIN or above" + BASIS;
    public static final String OWNER = "Requires the OWNER" + BASIS;
    public static final String RECENT_AUTH =
            " Also requires a recent sign-in; a stale session is rejected with 403 REAUTHENTICATION_REQUIRED.";
    public static final String RETRY_CONFLICTS = " Retrying conflicts (409) rather than creating a duplicate.";
    public static final String REVOKE_NOTE = "Revocation stops the invite showing as pending. A link already emailed "
            + "still verifies cryptographically; if it is used, the resulting account is disabled shortly afterwards.";

    private ApiDocs() {}
}
