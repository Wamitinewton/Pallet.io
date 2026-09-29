package io.pallet.gitintegration.security;

/** What a signed state was issued for. {@code INSTALL} is bound to an org; {@code AUTHORIZE} belongs to the user. */
public enum AuthorizationPurpose {
    INSTALL,
    AUTHORIZE;

    public boolean requiresOrg() {
        return this == INSTALL;
    }
}
