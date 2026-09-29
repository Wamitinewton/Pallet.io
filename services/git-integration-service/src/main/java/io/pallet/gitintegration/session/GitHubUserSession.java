package io.pallet.gitintegration.session;

import io.pallet.gitintegration.github.GitHubUserToken;
import java.time.Instant;
import java.util.Objects;

/** A Pallet user's short-lived GitHub identity. The token never leaves {@code session/} except into GitHubClient. */
public final class GitHubUserSession {

    private final GitHubUserToken token;
    private final long githubUserId;
    private final String githubLogin;
    private final Instant expiresAt;

    GitHubUserSession(GitHubUserToken token, long githubUserId, String githubLogin, Instant expiresAt) {
        this.token = Objects.requireNonNull(token, "token");
        this.githubUserId = githubUserId;
        this.githubLogin = Objects.requireNonNull(githubLogin, "githubLogin");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    GitHubUserToken token() {
        return token;
    }

    public long githubUserId() {
        return githubUserId;
    }

    public String githubLogin() {
        return githubLogin;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    @Override
    public String toString() {
        return "GitHubUserSession[token=<redacted>, githubUserId=%d, githubLogin=%s, expiresAt=%s]"
                .formatted(githubUserId, githubLogin, expiresAt);
    }
}
