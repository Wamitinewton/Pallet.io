package io.pallet.gitintegration.audit;

import java.util.Objects;

/** Who an audit record names: a Pallet user's {@code sub}, or {@link #SYSTEM} when GitHub or another service acted. */
public record Actor(String id) {

    public static final Actor SYSTEM = new Actor("system");

    public Actor {
        Objects.requireNonNull(id, "id");
    }

    public static Actor user(String sub) {
        return new Actor(sub);
    }
}
