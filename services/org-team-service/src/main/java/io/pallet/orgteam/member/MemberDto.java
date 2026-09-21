package io.pallet.orgteam.member;

import java.time.Instant;

public record MemberDto(
        String userId, String email, String displayName, Role role, MembershipStatus status, Instant joinedAt) {

    public static MemberDto of(Membership member) {
        return new MemberDto(
                member.getUserId(),
                member.getEmail(),
                member.getDisplayName(),
                member.getRole(),
                member.getStatus(),
                member.getJoinedAt());
    }
}
