package io.pallet.orgteam.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("pallet.orgteam")
public record OrgTeamProperties(
        @Valid @NotNull @DefaultValue Invites invites,
        @Valid @NotNull @DefaultValue Limits limits,
        @Valid @NotNull @DefaultValue Apps apps,
        @Valid @NotNull @DefaultValue Security security,
        @Valid @NotNull @DefaultValue Outbox outbox,
        @Valid @NotNull @DefaultValue Inbox inbox,
        @Valid @NotNull @DefaultValue Retention retention) {

    public record Invites(
            @NotNull @PositiveDuration @DefaultValue("PT72H")
            Duration ttl,

            @Positive @DefaultValue("50") int maxPendingPerOrg,
            @NotNull @PositiveDuration @DefaultValue("PT5M") Duration resendCooldown,
            @Positive @DefaultValue("4") int maxSends,

            @NotNull @NonNegativeDuration @DefaultValue("PT2M")
            Duration acceptGrace,

            @NotBlank @Pattern(regexp = ".*\\{token}.*", message = "must contain the {token} placeholder") @DefaultValue("http://localhost:5173/invites/{token}")
            String acceptUrlTemplate) {}

    public record Limits(
            @Positive @DefaultValue("100") int maxTeamsPerOrg,
            @Positive @DefaultValue("200") int maxAppsPerOrg) {}

    public record Apps(@Valid @NotNull @DefaultValue Regions regions) {

        public record Regions(
                @NotEmpty @DefaultValue({"us-east-1", "us-west-2", "eu-west-1", "eu-central-1", "ap-southeast-1", "af-south-1"})
                List<@NotBlank String> aws,

                @NotEmpty @DefaultValue({
                    "us-central1",
                    "us-east1",
                    "europe-west1",
                    "europe-west4",
                    "asia-southeast1",
                    "africa-south1"
                })
                List<@NotBlank String> gcp) {}
    }

    public record Security(
            @NotNull @PositiveDuration @DefaultValue("PT10M")
            Duration recentAuthWindow) {}

    public record Outbox(
            @DefaultValue("true") boolean enabled,

            @NotNull @PositiveDuration @DefaultValue("PT0.25S")
            Duration pollInterval,

            @Positive @DefaultValue("100") int batchSize,
            @Positive @DefaultValue("10") int maxAttempts,
            @NotNull @PositiveDuration @DefaultValue("P7D") Duration retention,
            @DefaultValue("7305121408") long advisoryLockKey,
            @NotNull @PositiveDuration @DefaultValue("PT1S") Duration brokerBackoffInitial,

            @NotNull @PositiveDuration @DefaultValue("PT30S")
            Duration brokerBackoffMax,

            @NotNull @PositiveDuration @DefaultValue("PT60S")
            Duration rowBackoffMax,

            @NotNull @PositiveDuration @DefaultValue("PT5S") Duration metricsRefreshInterval) {

        @AssertTrue(message = "broker-backoff-max must not be shorter than broker-backoff-initial") boolean isBrokerBackoffRangeValid() {
            return brokerBackoffInitial == null
                    || brokerBackoffMax == null
                    || brokerBackoffMax.compareTo(brokerBackoffInitial) >= 0;
        }
    }

    public record Inbox(
            @NotNull @PositiveDuration @DefaultValue("P14D") Duration retention) {}

    public record Retention(
            @DefaultValue("true") boolean enabled,
            @NotNull @PositiveDuration @DefaultValue("P90D") Duration terminalInvites,

            @NotNull @PositiveDuration @DefaultValue("P365D")
            Duration removedMemberships,

            @NotNull @PositiveDuration @DefaultValue("P30D") Duration deletedOrgs,

            @NotNull @PositiveDuration @DefaultValue("PT1M") Duration inviteExpiryInterval,

            @NotNull @PositiveDuration @DefaultValue("PT1H") Duration sweepInterval,
            @NotNull @PositiveDuration @DefaultValue("P1D") Duration dailySweepInterval,
            @Positive @DefaultValue("500") int sweepBatchSize,
            @Positive @DefaultValue("25") int orgPurgeBatchSize) {}
}
