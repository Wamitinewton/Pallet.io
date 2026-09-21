package io.pallet.orgteam.invite;

import io.pallet.orgteam.member.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A pending or past invite. The signed token and the accept link are never part of any response.")
public record InviteDto(
        UUID id,
        String email,
        Role role,
        InviteStatus status,
        String invitedByUserId,
        int sendCount,
        Instant expiresAt,
        Instant createdAt) {

    static InviteDto of(Invite invite, Instant now) {
        return new InviteDto(
                invite.getId(),
                invite.getEmail(),
                invite.getRole(),
                invite.effectiveStatus(now),
                invite.getInvitedByUserId(),
                invite.getSendCount(),
                invite.getExpiresAt(),
                invite.getCreatedAt());
    }
}
