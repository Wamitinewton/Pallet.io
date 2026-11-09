package io.pallet.build.security;

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

    /**
     * The upper-case name the membership projection stores.
     *
     * @throws IllegalStateException for a value the projection should never have stored
     */
    public static Role fromReadModel(String value) {
        return Arrays.stream(values())
                .filter(role -> role.name().equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown role in org_memberships"));
    }
}
