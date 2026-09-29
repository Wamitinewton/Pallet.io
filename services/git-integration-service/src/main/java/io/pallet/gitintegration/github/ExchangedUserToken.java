package io.pallet.gitintegration.github;

import java.time.Instant;

/** A user token fresh from a code exchange; {@code expiresAt} is null when the app's user tokens don't expire. */
public record ExchangedUserToken(GitHubUserToken token, Instant expiresAt) {}
