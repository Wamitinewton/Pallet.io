package io.pallet.orgteam.member;

import jakarta.validation.constraints.Size;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public record MemberFilter(
        MembershipStatus status, Role role, @Size(max = 100) String q) {

    public MemberFilter {
        status = status == null ? MembershipStatus.ACTIVE : status;
        q = q == null ? null : q.strip();
    }

    Set<Role> roles() {
        return role == null ? EnumSet.allOf(Role.class) : EnumSet.of(role);
    }

    String prefixPattern() {
        if (q == null || q.isEmpty()) {
            return "%";
        }
        String escaped =
                q.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return escaped + "%";
    }
}
