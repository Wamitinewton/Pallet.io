package io.pallet.common.outbox;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code pallet.outbox.*}. {@code schema}, {@code metrics-prefix} and {@code advisory-lock-key} have no default: two
 * services on one Postgres cluster must never share a relay lock, and each keeps its own metric names (ADR-0020).
 */
@Validated
@ConfigurationProperties("pallet.outbox")
public record OutboxProperties(
        @NotBlank @Pattern(regexp = "[a-z_][a-z0-9_]{0,62}", message = "must be a lowercase Postgres identifier") String schema,

        @NotBlank @Pattern(regexp = "[a-z][a-z0-9_.]{0,63}", message = "must be a dotted lowercase metric name") String metricsPrefix,

        @NotNull Long advisoryLockKey,
        @DefaultValue("true") boolean enabled,

        @NotNull @PositiveDuration @DefaultValue("PT0.25S") Duration pollInterval,

        @Positive @DefaultValue("100") int batchSize,
        @Positive @DefaultValue("10") int maxAttempts,
        @NotNull @PositiveDuration @DefaultValue("P7D") Duration retention,
        @NotNull @PositiveDuration @DefaultValue("PT1S") Duration brokerBackoffInitial,

        @NotNull @PositiveDuration @DefaultValue("PT30S") Duration brokerBackoffMax,

        @NotNull @PositiveDuration @DefaultValue("PT60S") Duration rowBackoffMax,

        @NotNull @PositiveDuration @DefaultValue("PT5S") Duration metricsRefreshInterval,

        @NotNull @PositiveDuration @DefaultValue("PT5S") Duration lockTimeout) {

    @AssertTrue(message = "broker-backoff-max must not be shorter than broker-backoff-initial") boolean isBrokerBackoffRangeValid() {
        return brokerBackoffInitial == null
                || brokerBackoffMax == null
                || brokerBackoffMax.compareTo(brokerBackoffInitial) >= 0;
    }

    String table(String name) {
        return schema + "." + name;
    }

    String metric(String name) {
        return metricsPrefix + "." + name;
    }
}
