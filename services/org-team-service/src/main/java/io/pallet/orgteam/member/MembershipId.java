package io.pallet.orgteam.member;

import java.io.Serializable;

public record MembershipId(String orgId, String userId) implements Serializable {}
