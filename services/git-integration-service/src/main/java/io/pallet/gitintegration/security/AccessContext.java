package io.pallet.gitintegration.security;

public record AccessContext(String orgId, String userId, Role role) {}
