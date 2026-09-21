package io.pallet.orgteam.member;

import java.util.Arrays;
import java.util.Locale;

public enum Role {
    VIEWER,
    DEVELOPER,
    ADMIN,
    OWNER;

    public boolean atLeast(Role other) {
        return compareTo(other) >= 0;
    }

    public String keycloakName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Role fromKeycloakName(String name) {
        return Arrays.stream(values())
                .filter(role -> role.keycloakName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Not a Keycloak role name: " + name));
    }
}
