package io.pallet.gitintegration.github;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.error.AppException;
import io.pallet.common.test.annotations.UnitTest;
import io.pallet.gitintegration.github.GitHubExceptions.AppCredentialRejectedException;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubForbiddenException;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubNotFoundException;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubRateLimitedException;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubRequestRejectedException;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubUnavailableException;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubUserTokenRejectedException;
import io.pallet.gitintegration.github.GitHubExceptions.InstallationTokenRejectedException;
import io.pallet.gitintegration.github.GitHubRequest.NotFoundMapper;
import io.pallet.gitintegration.github.GitHubResponseClassifier.Classification;
import io.pallet.gitintegration.github.GitHubResponseClassifier.Outcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@UnitTest
class GitHubResponseClassifierTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

    private static final GitHubCredential APP = new GitHubCredential.App(new AppJwt("jwt", NOW.plusSeconds(540)));
    private static final GitHubCredential INSTALLATION =
            new GitHubCredential.Installation(7, new InstallationToken("ghs_x", NOW.plusSeconds(3600)));
    private static final GitHubCredential USER = new GitHubCredential.User(new GitHubUserToken("ghu_x"));

    private final GitHubResponseClassifier classifier = new GitHubResponseClassifier(Clock.fixed(NOW, ZoneOffset.UTC));

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 204})
    void a2xxIsSuccess(int status) {
        Classification classification = classify(status, HttpHeaders.EMPTY, INSTALLATION);

        assertThat(classification.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(classification.isFailure()).isFalse();
    }

    @Test
    void a304IsNotModified() {
        Classification classification = classify(304, HttpHeaders.EMPTY, INSTALLATION);

        assertThat(classification.outcome()).isEqualTo(Outcome.NOT_MODIFIED);
        assertThat(classification.isFailure()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 410, 422})
    void aMissingResourceUsesTheDefaultMapper(int status) {
        Classification classification = classify(status, HttpHeaders.EMPTY, INSTALLATION);

        assertThat(classification.outcome()).isEqualTo(Outcome.NOT_FOUND);
        assertThat(classification.failure()).isInstanceOf(GitHubNotFoundException.class);
        assertThat(classification.failure()).isInstanceOf(AppException.class);
    }

    @Test
    void aMissingResourceUsesTheCallersMapper() {
        AppException domain = new GitHubForbiddenException();
        NotFoundMapper mapper = status -> domain;

        Classification classification =
                classifier.classify(HttpStatusCode.valueOf(422), HttpHeaders.EMPTY, INSTALLATION, mapper);

        assertThat(classification.failure()).isSameAs(domain);
    }

    @Test
    void a401OnAnInstallationTokenIsATokenRejection() {
        Classification classification = classify(401, HttpHeaders.EMPTY, INSTALLATION);

        assertThat(classification.outcome()).isEqualTo(Outcome.UNAUTHORIZED);
        assertThat(classification.failure()).isInstanceOf(InstallationTokenRejectedException.class);
    }

    @Test
    void a401OnAUserTokenAsksForAuthorization() {
        Classification classification = classify(401, HttpHeaders.EMPTY, USER);

        assertThat(classification.failure()).isInstanceOfSatisfying(GitHubUserTokenRejectedException.class, failure -> {
            assertThat(failure.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(failure.getErrorCode()).isEqualTo("GITHUB_AUTHORIZATION_REQUIRED");
        });
    }

    @Test
    void a401OnTheAppJwtCountsAgainstTheBreaker() {
        Classification classification = classify(401, HttpHeaders.EMPTY, APP);

        assertThat(classification.failure())
                .isInstanceOf(AppCredentialRejectedException.class)
                .isNotInstanceOf(AppException.class);
    }

    @Test
    void a403WithAnExhaustedBudgetIsRateLimitedUntilTheReset() {
        HttpHeaders headers = headers(
                Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", String.valueOf(NOW.getEpochSecond() + 120)));

        Classification classification = classify(403, headers, INSTALLATION);

        assertThat(classification.outcome()).isEqualTo(Outcome.RATE_LIMITED);
        assertThat(classification.failure()).isInstanceOfSatisfying(GitHubRateLimitedException.class, failure -> {
            assertThat(failure.resetAt()).isEqualTo(NOW.plusSeconds(120));
            assertThat(failure.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(failure.getErrorCode()).isEqualTo("GITHUB_RATE_LIMITED");
            assertThat(failure.getMeta()).containsEntry("retryAfter", 120L);
        });
    }

    @Test
    void a403WithRetryAfterIsASecondaryRateLimit() {
        Classification classification = classify(403, headers(Map.of("retry-after", "30")), INSTALLATION);

        assertThat(classification.failure())
                .isInstanceOfSatisfying(
                        GitHubRateLimitedException.class,
                        failure -> assertThat(failure.resetAt()).isEqualTo(NOW.plusSeconds(30)));
    }

    @Test
    void a403WithBudgetLeftIsAPermissionProblem() {
        HttpHeaders headers = headers(Map.of(
                "x-ratelimit-remaining", "4999", "x-ratelimit-reset", String.valueOf(NOW.getEpochSecond() + 120)));

        Classification classification = classify(403, headers, INSTALLATION);

        assertThat(classification.outcome()).isEqualTo(Outcome.FORBIDDEN);
        assertThat(classification.failure()).isInstanceOf(GitHubForbiddenException.class);
    }

    @Test
    void a403WithoutRateLimitHeadersIsAPermissionProblem() {
        assertThat(classify(403, HttpHeaders.EMPTY, INSTALLATION).failure())
                .isInstanceOf(GitHubForbiddenException.class);
    }

    @Test
    void a429WithRetryAfterIsRateLimited() {
        Classification classification = classify(429, headers(Map.of("retry-after", "5")), INSTALLATION);

        assertThat(classification.failure())
                .isInstanceOfSatisfying(
                        GitHubRateLimitedException.class,
                        failure -> assertThat(failure.resetAt()).isEqualTo(NOW.plusSeconds(5)));
    }

    @Test
    void a429WithoutHeadersWaitsTheDocumentedMinute() {
        assertThat(classify(429, HttpHeaders.EMPTY, INSTALLATION).failure())
                .isInstanceOfSatisfying(
                        GitHubRateLimitedException.class,
                        failure -> assertThat(failure.resetAt()).isEqualTo(NOW.plusSeconds(60)));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    void a5xxIsAPlainRuntimeFailureTheBreakerCounts(int status) {
        Classification classification = classify(status, HttpHeaders.EMPTY, INSTALLATION);

        assertThat(classification.outcome()).isEqualTo(Outcome.SERVER_ERROR);
        assertThat(classification.failure())
                .isInstanceOf(GitHubUnavailableException.class)
                .isNotInstanceOf(AppException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 400, 409})
    void anyOtherResponseIsARejectionNotAnOutage(int status) {
        Classification classification = classify(status, HttpHeaders.EMPTY, INSTALLATION);

        assertThat(classification.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(classification.failure()).isInstanceOf(GitHubRequestRejectedException.class);
    }

    @Test
    void noFailureMessageCarriesTheCredential() {
        for (GitHubCredential credential : new GitHubCredential[] {APP, INSTALLATION, USER}) {
            RuntimeException failure =
                    classify(401, HttpHeaders.EMPTY, credential).failure();
            assertThat(failure.getMessage()).doesNotContain("jwt", "ghs_x", "ghu_x");
        }
    }

    private Classification classify(int status, HttpHeaders headers, GitHubCredential credential) {
        return classifier.classify(HttpStatusCode.valueOf(status), headers, credential, NotFoundMapper.DEFAULT);
    }

    private static HttpHeaders headers(Map<String, String> values) {
        HttpHeaders headers = new HttpHeaders();
        values.forEach(headers::set);
        return headers;
    }
}
