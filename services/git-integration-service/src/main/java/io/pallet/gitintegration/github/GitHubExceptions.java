package io.pallet.gitintegration.github;

import io.pallet.common.error.AppException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * What a GitHub response is classified into. Every {@link AppException} here is ignored by the breaker and the retry;
 * only {@link GitHubUnavailableException} and {@link AppCredentialRejectedException} count against GitHub's health.
 */
public final class GitHubExceptions {

    private GitHubExceptions() {}

    /** The default for a 404, 410, or 422; callers that need a domain code pass their own mapper. */
    public static class GitHubNotFoundException extends AppException {

        public GitHubNotFoundException(int status) {
            super(
                    HttpStatus.NOT_FOUND,
                    "GITHUB_RESOURCE_NOT_FOUND",
                    "The resource was not found on GitHub.",
                    "GitHub answered " + status);
        }
    }

    public static class GitHubRateLimitedException extends AppException {

        private final Instant resetAt;

        public GitHubRateLimitedException(Instant resetAt, Instant now) {
            super(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "GITHUB_RATE_LIMITED",
                    "GitHub's rate limit for this installation is spent. Try again later.",
                    "GitHub rate limit reached until " + resetAt);
            this.resetAt = resetAt;
            withMeta(Map.of("retryAfter", retryAfterSeconds(resetAt, now)));
        }

        public Instant resetAt() {
            return resetAt;
        }

        private static long retryAfterSeconds(Instant resetAt, Instant now) {
            Duration wait = Duration.between(now, resetAt);
            long seconds = wait.toSeconds() + (wait.toNanosPart() > 0 ? 1 : 0);
            return Math.max(1, seconds);
        }
    }

    /** A 403 that is not a rate limit: the credential lacks a permission. Callers map it to their own code. */
    public static class GitHubForbiddenException extends AppException {

        public GitHubForbiddenException() {
            super(
                    HttpStatus.FORBIDDEN,
                    "GITHUB_FORBIDDEN",
                    "GitHub refused the request.",
                    "GitHub answered 403 without rate-limit headers");
        }
    }

    /** Any other 4xx or an unexpected redirect: deterministic, so retrying or counting it would be wrong. */
    public static class GitHubRequestRejectedException extends AppException {

        public GitHubRequestRejectedException(int status) {
            super(
                    HttpStatus.BAD_GATEWAY,
                    "GITHUB_REQUEST_REJECTED",
                    "GitHub rejected the request.",
                    "GitHub answered " + status);
        }
    }

    /** GitHub refused a cached installation token; the client evicts it and retries once with a fresh one. */
    public static class InstallationTokenRejectedException extends AppException {

        public InstallationTokenRejectedException() {
            super(
                    HttpStatus.BAD_GATEWAY,
                    "GITHUB_CREDENTIAL_REJECTED",
                    "GitHub rejected the installation's credentials.",
                    "GitHub answered 401 to an installation token");
        }
    }

    public static class GitHubUserTokenRejectedException extends AppException {

        public GitHubUserTokenRejectedException() {
            super(
                    HttpStatus.FORBIDDEN,
                    "GITHUB_AUTHORIZATION_REQUIRED",
                    "Authorize Pallet with GitHub to continue.",
                    "GitHub answered 401 to a user token");
        }
    }

    /**
     * GitHub refused the app's own JWT or OAuth client credentials, which fails every such call alike, so it counts
     * against the breaker.
     */
    public static class AppCredentialRejectedException extends RuntimeException {

        public AppCredentialRejectedException(String message) {
            super(message);
        }
    }

    /**
     * GitHub answered a code exchange with an {@code error} field: the code is unknown, used, or expired, or the client
     * credentials are wrong. A definite answer, so it is never retried or counted.
     */
    public static class UserAuthorizationRejectedException extends AppException {

        public UserAuthorizationRejectedException(String error) {
            super(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_AUTHORIZATION_STATE",
                    "The GitHub authorization could not be completed. Start again.",
                    "GitHub rejected the authorization code: " + error);
        }
    }

    /**
     * A write that is not safe to repeat failed in a way that leaves unknown whether GitHub applied it, so it was not
     * sent again. The caller finds out what GitHub holds before trying once more.
     */
    public static class WriteOutcomeUnknownException extends AppException {

        public WriteOutcomeUnknownException(String endpoint) {
            super(
                    HttpStatus.BAD_GATEWAY,
                    "GITHUB_WRITE_OUTCOME_UNKNOWN",
                    "GitHub did not confirm the change.",
                    "GitHub did not answer " + endpoint + ", which is not sent twice");
        }
    }

    public static class GitHubUnavailableException extends RuntimeException {

        public GitHubUnavailableException(int status) {
            super("GitHub answered " + status);
        }
    }
}
