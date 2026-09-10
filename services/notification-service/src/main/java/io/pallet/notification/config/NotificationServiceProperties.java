package io.pallet.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("pallet.notification")
public record NotificationServiceProperties(
        @DefaultValue Email email, @DefaultValue RateLimit rateLimit) {

    public record Email(String from) {}

    public record RateLimit(
            @DefaultValue("100") int permitsPerPeriod,
            @DefaultValue("1m") Duration period,
            @DefaultValue("30s") Duration sweepInterval,
            @DefaultValue("500") int sweepBatchLimit) {}
}
