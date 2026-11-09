package io.pallet.build.security;

public record AccessContext(String orgId, String userId, Role role) {}
