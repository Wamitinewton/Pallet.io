package io.pallet.gitintegration.github;

import org.jspecify.annotations.NonNull;

/** A GitHub user access token, only ever held inside an encrypted user session. */
public record GitHubUserToken(String value) {

    @Override
    public @NonNull String toString() {
        return "GitHubUserToken[<redacted>]";
    }
}
