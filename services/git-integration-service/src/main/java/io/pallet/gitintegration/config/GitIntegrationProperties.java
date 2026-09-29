package io.pallet.gitintegration.config;

import io.pallet.gitintegration.access.RepoPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * {@code pallet.git.*} (ARCHITECTURE.md §Configuration). Key material is checked by {@link SecretMaterialValidator},
 * not here, so a failure names the property without echoing its value.
 */
@Validated
@ConfigurationProperties("pallet.git")
public record GitIntegrationProperties(
        @Valid @NotNull GitHub github,
        @Valid @NotNull @DefaultValue Authorization authorization,
        @Valid @NotNull @DefaultValue UserSession userSession,
        @Valid @NotNull @DefaultValue Link link,
        @Valid @NotNull @DefaultValue RepositorySync repositorySync,
        @Valid @NotNull @DefaultValue Reverify reverify,
        @Valid @NotNull @DefaultValue UnusedInstallation unusedInstallation,
        @Positive @DefaultValue("30") int manualBuildsPerHour,
        @Valid @NotNull @DefaultValue Webhook webhook,
        @Valid @NotNull @DefaultValue Delivery delivery,
        @Valid @NotNull @DefaultValue Redelivery redelivery,
        @Valid @NotNull @DefaultValue Reconciler reconciler,
        @Valid @NotNull @DefaultValue RateLimit ratelimit,
        @Valid @NotNull @DefaultValue Tokens tokens,
        @Valid @NotNull @DefaultValue Push push,
        @Valid @NotNull @DefaultValue Checks checks,
        @Valid @NotNull @DefaultValue Retention retention) {

    public record GitHub(
            @NotBlank String appId,
            @NotBlank String clientId,
            @NotBlank String appSlug,
            String clientSecret,
            String privateKey,
            @NotNull @DefaultValue List<String> webhookSecrets,
            @NotNull @DefaultValue("https://api.github.com") URI apiBaseUrl,
            @NotNull @DefaultValue("https://github.com") URI webBaseUrl,
            @NotNull @PositiveDuration @DefaultValue("PT5S") Duration connectTimeout,

            @NotNull @PositiveDuration @DefaultValue("PT10S")
            Duration readTimeout) {

        @Override
        public @NonNull String toString() {
            return "GitHub[appId=%s, clientId=%s, appSlug=%s, apiBaseUrl=%s, webBaseUrl=%s, connectTimeout=%s, readTimeout=%s]"
                    .formatted(appId, clientId, appSlug, apiBaseUrl, webBaseUrl, connectTimeout, readTimeout);
        }
    }

    public record Authorization(
            String stateSigningKey,

            @NotNull @PositiveDuration @DefaultValue("PT10M")
            Duration stateTtl) {

        @Override
        public @NonNull String toString() {
            return "Authorization[stateTtl=" + stateTtl + "]";
        }
    }

    public record UserSession(
            String encryptionKey,
            @NotNull @PositiveDuration @DefaultValue("PT1H") Duration maxTtl) {

        @Override
        public @NonNull String toString() {
            return "UserSession[maxTtl=" + maxTtl + "]";
        }
    }

    /**
     * {@code maxInstallationPages} bounds the {@code GET /user/installations} walk that proves a caller can see an
     * installation; {@code maxPickerPages} bounds the repository picker's scan when it filters by name.
     */
    public record Link(
            @NotNull @DefaultValue("PUSH") RepoPermission minRepoPermission,
            @Positive @DefaultValue("10") int maxInstallationPages,
            @Positive @DefaultValue("10") int maxPickerPages) {}

    /**
     * {@code maxPages} bounds one installation's listing; a listing cut short upserts what it saw and deletes nothing.
     * Scheduled syncs queue behind {@code workers} threads, at most {@code queueCapacity} deep. Every {@code interval},
     * unless {@code periodic} is off, one instance syncs up to {@code maxPerRun} installations, least recently synced
     * first.
     */
    public record RepositorySync(
            @Positive @DefaultValue("100") int maxPages,
            @Positive @DefaultValue("2") int workers,
            @Positive @DefaultValue("100") int queueCapacity,
            @DefaultValue("true") boolean periodic,
            @NotNull @PositiveDuration @DefaultValue("P1D") Duration interval,
            @Positive @DefaultValue("1000") int maxPerRun) {}

    /**
     * Every {@code interval}, unless disabled, one instance re-checks up to {@code maxPerRun} active links, least
     * recently checked first; a link checked within {@code minAge} is not due yet. {@code metricsInterval} refreshes the
     * oldest-check gauge.
     */
    public record Reverify(
            @DefaultValue("true") boolean enabled,
            @NotNull @PositiveDuration @DefaultValue("P1D") Duration interval,
            @Positive @DefaultValue("5000") int maxPerRun,

            @NotNull @PositiveDuration @DefaultValue("PT12H")
            Duration minAge,

            @NotNull @PositiveDuration @DefaultValue("PT1M") Duration metricsInterval) {}

    /**
     * An installation no org has linked for {@code grace} is uninstalled. Every {@code sweepInterval}, unless disabled,
     * one instance uninstalls up to {@code maxPerRun}, longest unused first. {@code metricsInterval} refreshes the
     * unused-installation gauge.
     */
    public record UnusedInstallation(
            @NotNull @PositiveDuration @DefaultValue("P30D") Duration grace,
            @DefaultValue("true") boolean enabled,

            @NotNull @PositiveDuration @DefaultValue("P1D") Duration sweepInterval,

            @Positive @DefaultValue("100") int maxPerRun,

            @NotNull @PositiveDuration @DefaultValue("PT1M") Duration metricsInterval) {}

    public record Webhook(
            @NotNull @DefaultValue("25MB") DataSize maxBody,
            @Valid @NotNull @DefaultValue GithubIpAllowlist githubIpAllowlist) {

        public record GithubIpAllowlist(
                @DefaultValue("false") boolean enabled) {}

        /** The handler buffers the whole body in one array before verifying it, so the cap bounds heap per request. */
        public static final DataSize MAX_BODY_CEILING = DataSize.ofMegabytes(100);

        @AssertTrue(message = "max-body must be positive and at most 100MB") boolean isMaxBodyInRange() {
            return maxBody == null || (maxBody.toBytes() > 0 && maxBody.toBytes() <= MAX_BODY_CEILING.toBytes());
        }
    }

    public record Delivery(
            @DefaultValue("true") boolean enabled,

            @NotNull @PositiveDuration @DefaultValue("PT0.25S")
            Duration pollInterval,

            @Positive @DefaultValue("50") int batchSize,
            @Positive @DefaultValue("10") int maxAttempts,
            @Positive @DefaultValue("1") int workers,

            @NotNull @PositiveDuration @DefaultValue("PT30S")
            Duration lease,

            @Positive @DefaultValue("3") int maxLookupRounds,
            @NotNull @PositiveDuration @DefaultValue("PT1S") Duration backoffBase,

            @NotNull @PositiveDuration @DefaultValue("PT10M")
            Duration backoffMax,

            @NotNull @PositiveDuration @DefaultValue("PT5M") Duration unavailableBackoffMax,

            @NotNull @PositiveDuration @DefaultValue("PT5S") Duration rateLimitJitter,

            @NotNull @PositiveDuration @DefaultValue("PT15S")
            Duration metricsInterval) {}

    /**
     * Each run reads the app's delivery log back to {@code overlap} before its cursor, or {@code lookback}, whichever
     * is more recent, reading at most {@code maxPages} pages and requesting at most {@code maxPerRun} redeliveries.
     */
    public record Redelivery(
            @DefaultValue("true") boolean enabled,
            @NotNull @PositiveDuration @DefaultValue("PT5M") Duration interval,

            @NotNull @PositiveDuration @DefaultValue("PT24H")
            Duration lookback,

            @NotNull @PositiveDuration @DefaultValue("PT10M")
            Duration overlap,

            @Positive @DefaultValue("100") int maxPerRun,
            @Positive @DefaultValue("50") int maxPages) {}

    /** {@code maxPerRun} counts distinct repository branches fetched, however many links share one. */
    public record Reconciler(
            @DefaultValue("true") boolean enabled,

            @NotNull @PositiveDuration @DefaultValue("PT15M")
            Duration interval,

            @Positive @DefaultValue("500") int maxPerRun) {}

    public record RateLimit(
            @PositiveOrZero @DefaultValue("500") int reserve) {}

    public record Tokens(
            @NotNull @PositiveDuration @DefaultValue("PT5M") Duration refreshSkew,
            @Positive @DefaultValue("10000") int maxCached) {}

    public record Push(
            @NotNull @DefaultValue({"[skip ci]", "[ci skip]", "[pallet skip]"})
            List<@NotBlank String> skipMarkers) {}

    /**
     * The check run reporter: every {@code pollInterval}, unless disabled, one instance claims up to {@code batchSize}
     * pending check runs for {@code lease}, writing to at most {@code parallelism} installations at a time and one
     * check run at a time within each. A check run that fails {@code maxAttempts} times is given up. Until
     * {@code awaitDeploy} is on, a successful build completes the check without waiting for its deploy.
     */
    public record Checks(
            @DefaultValue("true") boolean enabled,
            @NotNull @PositiveDuration @DefaultValue("PT2S") Duration pollInterval,
            @Positive @DefaultValue("50") int batchSize,
            @NotNull @PositiveDuration @DefaultValue("PT2M") Duration lease,
            @Positive @DefaultValue("4") int parallelism,
            @Positive @DefaultValue("20") int maxAttempts,
            @DefaultValue("false") boolean awaitDeploy) {}

    /**
     * Unless disabled, payloads are nulled every {@code payloadSweepInterval} and every other sweep runs every
     * {@code sweepInterval}. A sweep deletes in transactions of at most {@code batchSize} rows, at most
     * {@code maxBatchesPerRun} of them per run. {@code terminalConnections} covers {@code DISCONNECTED} repo links,
     * {@code UNLINKED} installation links and {@code DELETED} installations.
     */
    public record Retention(
            @DefaultValue("true") boolean enabled,

            @NotNull @PositiveDuration @DefaultValue("PT1H") Duration payloadSweepInterval,

            @NotNull @PositiveDuration @DefaultValue("P1D") Duration sweepInterval,
            @Positive @DefaultValue("1000") int batchSize,
            @Positive @DefaultValue("1000") int maxBatchesPerRun,
            @NotNull @PositiveDuration @DefaultValue("P7D") Duration deliveryPayload,
            @NotNull @PositiveDuration @DefaultValue("P30D") Duration deliveries,
            @NotNull @PositiveDuration @DefaultValue("P1D") Duration authorizationStates,
            @NotNull @PositiveDuration @DefaultValue("P7D") Duration manualBuildRequests,
            @NotNull @PositiveDuration @DefaultValue("P30D") Duration checkRuns,
            @NotNull @PositiveDuration @DefaultValue("P90D") Duration terminalConnections,
            @NotNull @PositiveDuration @DefaultValue("P90D") Duration deletedApps,
            @NotNull @PositiveDuration @DefaultValue("P90D") Duration deletedOrgs) {}
}
