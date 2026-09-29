package io.pallet.gitintegration.security;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Ordered from most to least privileged, matching Keycloak's realm roles. */
public enum Role {
    OWNER,
    ADMIN,
    DEVELOPER,
    VIEWER;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean atLeast(Role floor) {
        return compareTo(floor) <= 0;
    }

    /** Exact lowercase Keycloak names only; anything else is empty, never mapped to something close. */
    public static Optional<Role> fromWireName(String value) {
        return Arrays.stream(values())
                .filter(role -> role.wireName().equals(value))
                .findFirst();
    }

    /** @throws IllegalStateException for a value the membership projection should never have stored */
    public static Role fromReadModel(String value) {
        return fromWireName(value).orElseThrow(() -> new IllegalStateException("unknown role in org_memberships"));
    }
}
