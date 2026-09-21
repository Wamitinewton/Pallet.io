package io.pallet.orgteam.outbox.chaos;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.security.SignedTokenTestConfiguration;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * One shared configuration for every outbox chaos test: the real scheduled relay on a tight poll
 * loop, short backoff ceilings so recovery is observable in seconds, and a pool large enough for
 * several relays plus concurrent writers (each relay needs two connections).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, SignedTokenTestConfiguration.class})
@TestPropertySource(
        properties = {
            "pallet.orgteam.outbox.enabled=true",
            "pallet.orgteam.outbox.poll-interval=PT0.05S",
            "pallet.orgteam.outbox.broker-backoff-max=PT1S",
            "pallet.orgteam.outbox.row-backoff-max=PT1S",
            "spring.datasource.hikari.maximum-pool-size=24",
            "management.endpoint.health.show-details=always"
        })
@interface OutboxChaosTest {}
