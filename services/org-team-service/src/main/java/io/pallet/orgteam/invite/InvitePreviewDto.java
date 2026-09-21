package io.pallet.orgteam.invite;

import io.pallet.orgteam.member.Role;
import java.time.Instant;

public record InvitePreviewDto(String orgName, Role role, String inviterName, String maskedEmail, Instant expiresAt) {

    private static final String MASK = "***";

    static InvitePreviewDto of(String orgName, Invite invite, String inviterName) {
        return new InvitePreviewDto(
                orgName, invite.getRole(), inviterName, mask(invite.getEmail()), invite.getExpiresAt());
    }

    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return MASK;
        }
        return email.substring(0, email.offsetByCodePoints(0, 1)) + MASK + email.substring(at);
    }
}
