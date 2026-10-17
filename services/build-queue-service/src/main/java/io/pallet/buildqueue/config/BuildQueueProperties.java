package io.pallet.buildqueue.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code pallet.build-queue.*} (ARCHITECTURE.md §Configuration). Rules that span more than one key are checked by
 * {@link BuildQueuePropertiesValidator}.
 */
@Validated
@ConfigurationProperties(BuildQueueProperties.PREFIX)
public record BuildQueueProperties(
        @Valid @NotNull @DefaultValue Dispatch dispatch,
        @Valid @NotNull @DefaultValue Build build,
        @Valid @NotNull @DefaultValue Lease lease,
        @Valid @NotNull @DefaultValue Queue queue,
        @Valid @NotNull @DefaultValue Limits limits,
        @Valid @NotNull @DefaultValue Retention retention,
        @Valid @NotNull @DefaultValue Observability observability) {

    public static final String PREFIX = "pallet.build-queue";

    /**
     * {@code globalConcurrency} is the number of builds build-service is provisioned to run at once; a round
     * dispatches at most {@code batchSize}. Each dispatch must start within {@code startTimeout}, and a build is
     * dispatched at most {@code maxAttempts} times. {@code wakeupEnabled} decides whether this instance listens for
     * dispatch notifications and polls; turning it off leaves rounds to other instances.
     */
    public record Dispatch(
            @Positive @DefaultValue("4") int globalConcurrency,
            @Positive @DefaultValue("50") int batchSize,
            @NotNull @PositiveDuration @DefaultValue("PT1S") Duration pollInterval,
            @NotNull @PositiveDuration @DefaultValue("PT5M") Duration startTimeout,
            @Positive @DefaultValue("3") int maxAttempts,
            @DefaultValue("false") boolean paused,
            @DefaultValue("true") boolean wakeupEnabled) {}

    /** {@code maxDuration} is carried on {@code build.dispatched} as {@code maxDurationSeconds}. */
    public record Build(
            @NotNull @PositiveDuration @DefaultValue("PT30M")
            Duration maxDuration) {}

    /**
     * A running build's lease is {@code build.maxDuration + grace}. Every {@code sweepInterval} the sweeper acts on at
     * most {@code batchSize} builds per deadline. {@code sweeperEnabled} decides whether this instance sweeps; turning
     * it off leaves sweeps to other instances.
     */
    public record Lease(
            @NotNull @PositiveDuration @DefaultValue("PT5M") Duration grace,

            @NotNull @PositiveDuration @DefaultValue("PT15S")
            Duration sweepInterval,

            @NotNull @PositiveDuration @DefaultValue("PT3S") Duration reachabilityTimeout,
            @Positive @DefaultValue("100") int batchSize,
            @DefaultValue("true") boolean sweeperEnabled) {}

    public record Queue(
            @NotNull @PositiveDuration @DefaultValue("PT24H")
            Duration maxAge) {}

    /**
     * Every org uses {@code defaultProfile} unless {@code orgProfiles} maps its id to another entry of
     * {@code profiles}. Setting {@code profiles} replaces the built-in ones entirely.
     */
    public record Limits(
            @NotBlank @DefaultValue(DEFAULT_PROFILE) String defaultProfile,
            Map<String, @Valid @NotNull Profile> profiles,
            Map<String, String> orgProfiles) {

        public static final String DEFAULT_PROFILE = "default";
        public static final String TEAM_PROFILE = "team";

        static final Map<String, Profile> BUILT_IN_PROFILES =
                Map.of(DEFAULT_PROFILE, new Profile(1, 50), TEAM_PROFILE, new Profile(3, 200));

        public Limits {
            profiles = profiles == null ? BUILT_IN_PROFILES : Map.copyOf(profiles);
            orgProfiles = orgProfiles == null ? Map.of() : Map.copyOf(orgProfiles);
        }

        /** The {@code org-profiles} override for the org, else {@code default-profile}. */
        public String profileName(String orgId) {
            return orgProfiles.getOrDefault(orgId, defaultProfile);
        }

        public Profile forOrg(String orgId) {
            return profiles.get(profileName(orgId));
        }
    }

    public record Profile(
            @Positive int maxConcurrent, @Positive int maxQueued) {}

    /**
     * Terminal builds are kept {@code terminalBuilds} after they finish and app and org tombstones {@code tombstones}
     * after deletion. Every {@code interval} each sweep deletes in batches of {@code batchSize}, at most
     * {@code maxBatchesPerRun} of them. {@code enabled} decides whether this instance sweeps; turning it off leaves
     * retention to other instances.
     */
    public record Retention(
            @NotNull @PositiveDuration @DefaultValue("P30D") Duration terminalBuilds,
            @NotNull @PositiveDuration @DefaultValue("P30D") Duration tombstones,
            @Positive @DefaultValue("500") int batchSize,
            @Positive @DefaultValue("10") int maxBatchesPerRun,
            @NotNull @PositiveDuration @DefaultValue("PT1H") Duration interval,
            @DefaultValue("true") boolean enabled) {}

    /** The queue's polled gauges are read from the database every {@code gaugeInterval}. */
    public record Observability(
            @NotNull @PositiveDuration @DefaultValue("PT15S")
            Duration gaugeInterval) {}
}
