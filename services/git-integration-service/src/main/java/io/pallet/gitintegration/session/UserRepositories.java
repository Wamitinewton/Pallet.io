package io.pallet.gitintegration.session;

import java.util.List;

/** One page of the repositories a user and an installation can both reach, as the user's own token sees them. */
public record UserRepositories(int totalCount, List<UserRepository> repositories) {

    public UserRepositories {
        repositories = List.copyOf(repositories);
    }

    /** {@code permission} is the user's highest: {@code admin}, {@code maintain}, {@code push}, {@code triage}, {@code pull}, or null. */
    public record UserRepository(
            long id, String fullName, String defaultBranch, boolean isPrivate, boolean archived, String permission) {

        public String name() {
            return fullName.substring(fullName.indexOf('/') + 1);
        }
    }
}
