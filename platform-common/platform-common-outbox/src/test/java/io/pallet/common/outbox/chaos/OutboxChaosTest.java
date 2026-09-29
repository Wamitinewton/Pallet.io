package io.pallet.common.outbox.chaos;

import io.pallet.common.outbox.OutboxIntegrationTest;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * One shared configuration for every outbox chaos test: the real scheduled relay on a tight poll
 * loop, short backoff ceilings so recovery is observable in seconds, and a pool large enough for
 * several relays plus concurrent writers (each relay needs two connections).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@OutboxIntegrationTest
@TestPropertySource(
        properties = {
            "pallet.outbox.enabled=true",
            "pallet.outbox.poll-interval=PT0.05S",
            "pallet.outbox.broker-backoff-max=PT1S",
            "pallet.outbox.row-backoff-max=PT1S",
            "spring.datasource.hikari.maximum-pool-size=24"
        })
@interface OutboxChaosTest {}
