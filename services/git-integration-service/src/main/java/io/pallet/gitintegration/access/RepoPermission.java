package io.pallet.gitintegration.access;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** The GitHub repository permissions that can vouch for a link, least to most privileged. */
public enum RepoPermission {
    PUSH,
    MAINTAIN,
    ADMIN;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean atLeast(RepoPermission floor) {
        return compareTo(floor) >= 0;
    }

    /** {@code triage}, {@code pull}, and anything unknown are empty: below every floor. */
    public static Optional<RepoPermission> fromWireName(String value) {
        return Arrays.stream(values())
                .filter(permission -> permission.wireName().equals(value))
                .findFirst();
    }
}
