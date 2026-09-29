package io.pallet.gitintegration.github;

import java.util.Optional;

/** The credential a request is made with, which also fixes the resilience policy it runs under. */
sealed interface GitHubCredential {

    String API_POLICY = "github-api";
    String USER_POLICY = "github-user";

    String policy();

    Optional<String> authorization();

    record App(AppJwt jwt) implements GitHubCredential {

        @Override
        public String policy() {
            return API_POLICY;
        }

        @Override
        public Optional<String> authorization() {
            return Optional.of("Bearer " + jwt.value());
        }
    }

    record Installation(long installationId, InstallationToken token) implements GitHubCredential {

        @Override
        public String policy() {
            return API_POLICY;
        }

        @Override
        public Optional<String> authorization() {
            return Optional.of("Bearer " + token.value());
        }
    }

    /** The app's OAuth client, for exchanging a user authorization code; its credentials travel in the body. */
    record OAuthClient() implements GitHubCredential {

        @Override
        public String policy() {
            return USER_POLICY;
        }

        @Override
        public Optional<String> authorization() {
            return Optional.empty();
        }
    }

    record User(GitHubUserToken token) implements GitHubCredential {

        @Override
        public String policy() {
            return USER_POLICY;
        }

        @Override
        public Optional<String> authorization() {
            return Optional.of("Bearer " + token.value());
        }
    }
}
