package io.pallet.gitintegration.github;

import static io.pallet.gitintegration.observability.MetricsCatalog.*;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.http.HttpHeaders;

/**
 * The latest known GitHub budget per installation, learned from the rate-limit headers of every installation-token
 * response. Background jobs ask it before spending budget that user-facing work may need.
 */
public final class GitHubRateLimitGuard {

    private record Budget(long remaining, Instant resetAt) {

        /** Responses can arrive out of order: a later window wins, and within one window the lowest count wins. */
        Budget merge(Budget observed) {
            if (observed.resetAt.isAfter(resetAt)) {
                return observed;
            }
            if (observed.resetAt.isBefore(resetAt)) {
                return this;
            }
            return observed.remaining < remaining ? observed : this;
        }
    }

    private final ConcurrentMap<Long, Budget> budgets = new ConcurrentHashMap<>();
    private final int reserve;
    private final Clock clock;

    public GitHubRateLimitGuard(GitIntegrationProperties properties, Clock clock, MeterRegistry meters) {
        this.reserve = properties.ratelimit().reserve();
        this.clock = clock;
        Gauge.builder(GITHUB_RATELIMIT_LOW_INSTALLATIONS, this, GitHubRateLimitGuard::lowInstallations)
                .description("Installations whose GitHub budget is below the background-work reserve")
                .register(meters);
    }

    void record(long installationId, HttpHeaders headers) {
        Optional<Long> remaining =
                GitHubResponseClassifier.parseLong(headers.getFirst(GitHubResponseClassifier.RATELIMIT_REMAINING));
        Optional<Long> reset =
                GitHubResponseClassifier.parseLong(headers.getFirst(GitHubResponseClassifier.RATELIMIT_RESET));
        if (remaining.isEmpty() || reset.isEmpty() || remaining.get() < 0) {
            return;
        }
        observe(installationId, new Budget(remaining.get(), Instant.ofEpochSecond(reset.get())));
    }

    void recordExhausted(long installationId, Instant until) {
        observe(installationId, new Budget(0, until));
    }

    /** True unless the installation's live budget is under the reserve; an unknown budget is allowed. */
    public boolean allowBackground(long installationId) {
        return live(installationId).map(budget -> budget.remaining() >= reserve).orElse(true);
    }

    public Optional<Instant> exhaustedUntil(long installationId) {
        return live(installationId).filter(budget -> budget.remaining() == 0).map(Budget::resetAt);
    }

    public int lowInstallations() {
        Instant now = clock.instant();
        budgets.values().removeIf(budget -> !budget.resetAt().isAfter(now));
        return (int) budgets.values().stream()
                .filter(budget -> budget.remaining() < reserve)
                .count();
    }

    private void observe(long installationId, Budget observed) {
        Instant now = clock.instant();
        budgets.merge(
                installationId,
                observed,
                (current, incoming) -> current.resetAt().isAfter(now) ? current.merge(incoming) : incoming);
    }

    private Optional<Budget> live(long installationId) {
        return Optional.ofNullable(budgets.get(installationId))
                .filter(budget -> budget.resetAt().isAfter(clock.instant()));
    }
}
