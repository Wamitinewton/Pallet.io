package io.pallet.buildqueue.security;

public record AccessContext(String orgId, String userId, Role role) {}
