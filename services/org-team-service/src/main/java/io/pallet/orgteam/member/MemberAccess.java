package io.pallet.orgteam.member;

import io.pallet.orgteam.org.OrgStatus;

public record MemberAccess(OrgStatus orgStatus, Role role, MembershipStatus membershipStatus) {}
