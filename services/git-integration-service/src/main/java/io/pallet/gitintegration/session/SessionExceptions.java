package io.pallet.gitintegration.session;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

public final class SessionExceptions {

    private SessionExceptions() {}

    /** No usable GitHub user session: none, expired, or GitHub rejected its token. The dashboard re-authorizes. */
    public static class GitHubAuthorizationRequiredException extends AppException {

        public GitHubAuthorizationRequiredException() {
            super(
                    HttpStatus.FORBIDDEN,
                    "GITHUB_AUTHORIZATION_REQUIRED",
                    "Authorize Pallet with GitHub to continue.",
                    "No usable GitHub user session");
        }
    }

    public static class GitHubSessionNotFoundException extends AppException {

        public GitHubSessionNotFoundException() {
            super(
                    HttpStatus.NOT_FOUND,
                    "GITHUB_SESSION_NOT_FOUND",
                    "There is no GitHub session.",
                    "No GitHub user session for the caller");
        }
    }
}
