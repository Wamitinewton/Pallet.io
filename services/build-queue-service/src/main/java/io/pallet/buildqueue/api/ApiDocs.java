package io.pallet.buildqueue.api;

/** Wording shared by every operation, kept identical to the other services' API docs. */
public final class ApiDocs {

    private static final String BASIS = ", judged from your current membership in this org, not your token's roles.";

    public static final String ANY_MEMBER = "Requires any active member of the organization" + BASIS;
    public static final String DEVELOPER = "Requires DEVELOPER or above" + BASIS;

    public static final String MEMBERSHIP_404 = "ORG_NOT_FOUND";
    public static final String MEMBERSHIP_403 = "NOT_A_MEMBER or INSUFFICIENT_ROLE";

    private ApiDocs() {}
}
