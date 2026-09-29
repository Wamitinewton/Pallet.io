package io.pallet.gitintegration.access;

import java.util.Locale;

/**
 * What GitHub said about one verifier. Only {@link Lost} disconnects, and it only ever comes from a definite answer:
 * a role below the floor, or the verifier's account gone.
 */
sealed interface ReverifyOutcome {

    enum Kind {
        CONFIRMED,
        LOST,
        UNKNOWN;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    Kind kind();

    /** @param login the verifier's login now, which may differ from the stored one after a rename */
    record Confirmed(RepoPermission permission, String login) implements ReverifyOutcome {

        @Override
        public Kind kind() {
            return Kind.CONFIRMED;
        }
    }

    /** @param currentRole the verifier's role now, {@code none} when the account no longer exists */
    record Lost(String login, String currentRole) implements ReverifyOutcome {

        @Override
        public Kind kind() {
            return Kind.LOST;
        }
    }

    record Unknown(Reason reason) implements ReverifyOutcome {

        enum Reason {
            REPOSITORY_NOT_FOUND,
            LOGIN_UNSETTLED,
            RATE_LIMITED,
            GITHUB_UNAVAILABLE,
            ERROR
        }

        @Override
        public Kind kind() {
            return Kind.UNKNOWN;
        }
    }
}
