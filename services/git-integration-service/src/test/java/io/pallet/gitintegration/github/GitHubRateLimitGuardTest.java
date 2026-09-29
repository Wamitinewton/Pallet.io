package io.pallet.gitintegration.github;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.common.test.annotations.UnitTest;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.http.HttpHeaders;

@UnitTest
class GitHubRateLimitGuardTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final Instant RESET = NOW.plus(Duration.ofMinutes(30));

    private final MutableClock clock = new MutableClock(NOW);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final GitHubRateLimitGuard guard = new GitHubRateLimitGuard(properties(), clock, meters);

    @Test
    void anUnknownInstallationIsAllowed() {
        assertThat(guard.allowBackground(1)).isTrue();
        assertThat(guard.exhaustedUntil(1)).isEmpty();
    }

    @Test
    void aBudgetAtOrAboveTheReserveAllowsBackgroundWork() {
        guard.record(1, headers(500, RESET));

        assertThat(guard.allowBackground(1)).isTrue();
    }

    @Test
    void aBudgetBelowTheReserveStopsBackgroundWork() {
        guard.record(1, headers(499, RESET));

        assertThat(guard.allowBackground(1)).isFalse();
        assertThat(guard.exhaustedUntil(1)).isEmpty();
    }

    @Test
    void aSpentBudgetReportsWhenItResets() {
        guard.record(1, headers(0, RESET));

        assertThat(guard.exhaustedUntil(1)).contains(RESET);
    }

    @Test
    void aBudgetWhoseResetHasPassedIsTreatedAsUnknown() {
        guard.record(1, headers(0, RESET));

        clock.advance(Duration.ofMinutes(30));

        assertThat(guard.allowBackground(1)).isTrue();
        assertThat(guard.exhaustedUntil(1)).isEmpty();
    }

    @Test
    void aLateResponseFromTheSameWindowNeverRaisesTheCount() {
        guard.record(1, headers(100, RESET));
        guard.record(1, headers(900, RESET));

        assertThat(guard.allowBackground(1)).isFalse();
    }

    @Test
    void aNewWindowReplacesTheOldOne() {
        guard.record(1, headers(0, RESET));
        guard.record(1, headers(4999, RESET.plus(Duration.ofHours(1))));

        assertThat(guard.allowBackground(1)).isTrue();
    }

    @Test
    void aRateLimitResponseMarksTheInstallationExhausted() {
        guard.record(1, headers(3000, RESET));

        guard.recordExhausted(1, RESET);

        assertThat(guard.exhaustedUntil(1)).contains(RESET);
    }

    @Test
    void malformedHeadersAreIgnored() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-ratelimit-remaining", "lots");
        headers.set("x-ratelimit-reset", String.valueOf(RESET.getEpochSecond()));

        guard.record(1, headers);

        assertThat(guard.allowBackground(1)).isTrue();
    }

    @Test
    void theGaugeCountsLiveInstallationsUnderTheReserve() {
        guard.record(1, headers(10, RESET));
        guard.record(2, headers(499, RESET));
        guard.record(3, headers(4000, RESET));
        guard.record(4, headers(0, NOW.plusSeconds(60)));

        assertThat(meters.get(GitHubRateLimitGuard.LOW_INSTALLATIONS).gauge().value())
                .isEqualTo(3.0);

        clock.advance(Duration.ofMinutes(1));

        assertThat(guard.lowInstallations()).isEqualTo(2);
        assertThat(meters.get(GitHubRateLimitGuard.LOW_INSTALLATIONS)
                        .gauge()
                        .getId()
                        .getTags())
                .isEmpty();
    }

    private static HttpHeaders headers(long remaining, Instant reset) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-ratelimit-remaining", String.valueOf(remaining));
        headers.set("x-ratelimit-reset", String.valueOf(reset.getEpochSecond()));
        return headers;
    }

    private static GitIntegrationProperties properties() {
        return new Binder(new MapConfigurationPropertySource(Map.of(
                        "pallet.git.github.app-id", "1",
                        "pallet.git.github.client-id", "client",
                        "pallet.git.github.app-slug", "pallet")))
                .bind("pallet.git", Bindable.of(GitIntegrationProperties.class))
                .get();
    }
}
