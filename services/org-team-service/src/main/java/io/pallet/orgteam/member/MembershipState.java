package io.pallet.orgteam.member;

/** A membership's published state, for writes that bypass the entity. */
public record MembershipState(String orgId, String userId, Role role, MembershipStatus status, long version) {}
