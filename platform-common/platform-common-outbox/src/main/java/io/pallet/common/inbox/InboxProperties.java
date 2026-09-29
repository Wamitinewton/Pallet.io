package io.pallet.common.inbox;

import io.pallet.common.outbox.PositiveDuration;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("pallet.inbox")
public record InboxProperties(
        @NotNull @PositiveDuration @DefaultValue("P14D") Duration retention) {}
