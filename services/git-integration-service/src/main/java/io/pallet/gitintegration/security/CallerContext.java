package io.pallet.gitintegration.security;

/** The authenticated Pallet user, for endpoints that belong to the user rather than to an org. */
public record CallerContext(String userId) {}
