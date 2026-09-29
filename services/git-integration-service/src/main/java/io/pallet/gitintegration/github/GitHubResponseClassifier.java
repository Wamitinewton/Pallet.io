package io.pallet.gitintegration.github;

import io.pallet.gitintegration.github.GitHubExceptions.AppCredentialRejectedException;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubForbiddenException;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubRateLimitedException;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubRequestRejectedException;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubUnavailableException;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubUserTokenRejectedException;
import io.pallet.gitintegration.github.GitHubExceptions.InstallationTokenRejectedException;
import io.pallet.gitintegration.github.GitHubRequest.NotFoundMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

/** Turns a GitHub response into success or the exception ARCHITECTURE.md §Talking to GitHub assigns to it. */
final class GitHubResponseClassifier {

    static final String RETRY_AFTER = "retry-after";
    static final String RATELIMIT_REMAINING = "x-ratelimit-remaining";
    static final String RATELIMIT_RESET = "x-ratelimit-reset";

    /** GitHub's documented wait for a secondary rate limit that names no time. */
    private static final Duration UNSPECIFIED_RATE_LIMIT_WAIT = Duration.ofMinutes(1);

    private final Clock clock;

    GitHubResponseClassifier(Clock clock) {
        this.clock = clock;
    }

    enum Outcome {
        SUCCESS,
        NOT_MODIFIED,
        NOT_FOUND,
        UNAUTHORIZED,
        RATE_LIMITED,
        FORBIDDEN,
        REJECTED,
        SERVER_ERROR,
        ERROR;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    record Classification(Outcome outcome, RuntimeException failure) {

        boolean isFailure() {
            return failure != null;
        }
    }

    Classification classify(
            HttpStatusCode status, HttpHeaders headers, GitHubCredential credential, NotFoundMapper notFound) {
        int code = status.value();
        if (status.is2xxSuccessful()) {
            return new Classification(Outcome.SUCCESS, null);
        }
        if (code == 304) {
            return new Classification(Outcome.NOT_MODIFIED, null);
        }
        if (status.is5xxServerError()) {
            return new Classification(Outcome.SERVER_ERROR, new GitHubUnavailableException(code));
        }
        if (code == 404 || code == 410 || code == 422) {
            return new Classification(Outcome.NOT_FOUND, notFound.map(code));
        }
        if (code == 401) {
            return new Classification(Outcome.UNAUTHORIZED, unauthorized(credential));
        }
        if (code == 403 || code == 429) {
            Instant now = clock.instant();
            Optional<Instant> resetAt = rateLimitReset(headers, now);
            if (resetAt.isPresent() || code == 429) {
                Instant until = resetAt.orElse(now.plus(UNSPECIFIED_RATE_LIMIT_WAIT));
                return new Classification(Outcome.RATE_LIMITED, new GitHubRateLimitedException(until, now));
            }
            return new Classification(Outcome.FORBIDDEN, new GitHubForbiddenException());
        }
        return new Classification(Outcome.REJECTED, new GitHubRequestRejectedException(code));
    }

    private static RuntimeException unauthorized(GitHubCredential credential) {
        return switch (credential) {
            case GitHubCredential.Installation ignored -> new InstallationTokenRejectedException();
            case GitHubCredential.User ignored -> new GitHubUserTokenRejectedException();
            case GitHubCredential.App ignored ->
                new AppCredentialRejectedException("GitHub answered 401 to the app JWT");
            case GitHubCredential.OAuthClient ignored ->
                new AppCredentialRejectedException("GitHub answered 401 to the app's OAuth client credentials");
        };
    }

    private static Optional<Instant> rateLimitReset(HttpHeaders headers, Instant now) {
        Optional<Long> retryAfter = parseLong(headers.getFirst(RETRY_AFTER));
        if (retryAfter.isPresent() && retryAfter.get() >= 0) {
            return Optional.of(now.plusSeconds(retryAfter.get()));
        }
        if (parseLong(headers.getFirst(RATELIMIT_REMAINING)).filter(r -> r == 0).isPresent()) {
            return Optional.of(parseLong(headers.getFirst(RATELIMIT_RESET))
                    .map(Instant::ofEpochSecond)
                    .filter(reset -> reset.isAfter(now))
                    .orElse(now.plus(UNSPECIFIED_RATE_LIMIT_WAIT)));
        }
        return Optional.empty();
    }

    static Optional<Long> parseLong(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value.strip()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
