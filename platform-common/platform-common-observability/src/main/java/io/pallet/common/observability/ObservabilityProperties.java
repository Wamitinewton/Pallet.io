package io.pallet.common.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunables for {@code platform-common-observability}. Everything is on by default; a service
 * turns a piece off through config, never by dropping the dependency.
 */
@ConfigurationProperties("pallet.observability")
public record ObservabilityProperties(
    @DefaultValue("true") boolean methodMetricsEnabled,
    @DefaultValue("true") boolean correlationFilterEnabled,
    @DefaultValue(CorrelationId.HEADER) String correlationHeader) {
}
