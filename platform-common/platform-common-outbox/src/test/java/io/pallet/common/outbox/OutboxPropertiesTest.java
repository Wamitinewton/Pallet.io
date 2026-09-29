package io.pallet.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.inbox.InboxProperties;
import io.pallet.common.test.annotations.UnitTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

@UnitTest
class OutboxPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Enable.class);

    private static final String[] REQUIRED = {
        "pallet.outbox.schema=git_integration", "pallet.outbox.metrics-prefix=git", "pallet.outbox.advisory-lock-key=42"
    };

    @Test
    void theRequiredKeysBindWithTheDocumentedDefaults() {
        runner.withPropertyValues(REQUIRED).run(context -> {
            assertThat(context).hasNotFailed();
            OutboxProperties properties = context.getBean(OutboxProperties.class);
            assertThat(properties.schema()).isEqualTo("git_integration");
            assertThat(properties.metricsPrefix()).isEqualTo("git");
            assertThat(properties.advisoryLockKey()).isEqualTo(42L);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.pollInterval()).isEqualTo(Duration.ofMillis(250));
            assertThat(properties.batchSize()).isEqualTo(100);
            assertThat(properties.maxAttempts()).isEqualTo(10);
            assertThat(properties.retention()).isEqualTo(Duration.ofDays(7));
            assertThat(properties.brokerBackoffInitial()).isEqualTo(Duration.ofSeconds(1));
            assertThat(properties.brokerBackoffMax()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.rowBackoffMax()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.lockTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.table("outbox_events")).isEqualTo("git_integration.outbox_events");
            assertThat(properties.metric(OutboxMetrics.PENDING)).isEqualTo("git.outbox.pending");
        });
    }

    @Test
    void aMissingSchemaFailsBinding() {
        runner.withPropertyValues("pallet.outbox.metrics-prefix=git", "pallet.outbox.advisory-lock-key=42")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aMissingMetricsPrefixFailsBinding() {
        runner.withPropertyValues("pallet.outbox.schema=git_integration", "pallet.outbox.advisory-lock-key=42")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aMissingAdvisoryLockKeyFailsBinding() {
        runner.withPropertyValues("pallet.outbox.schema=git_integration", "pallet.outbox.metrics-prefix=git")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aSchemaThatIsNotAPlainIdentifierFailsBinding() {
        runner.withPropertyValues(
                        "pallet.outbox.schema=public; DROP TABLE x",
                        "pallet.outbox.metrics-prefix=git",
                        "pallet.outbox.advisory-lock-key=42")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aBrokerBackoffCeilingBelowItsStartFailsBinding() {
        runner.withPropertyValues(REQUIRED)
                .withPropertyValues(
                        "pallet.outbox.broker-backoff-initial=PT10S", "pallet.outbox.broker-backoff-max=PT5S")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aZeroPollIntervalFailsBinding() {
        runner.withPropertyValues(REQUIRED)
                .withPropertyValues("pallet.outbox.poll-interval=PT0S")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aZeroBatchSizeFailsBinding() {
        runner.withPropertyValues(REQUIRED)
                .withPropertyValues("pallet.outbox.batch-size=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theInboxRetentionDefaultsToFourteenDays() {
        runner.withPropertyValues(REQUIRED)
                .run(context -> assertThat(
                                context.getBean(InboxProperties.class).retention())
                        .isEqualTo(Duration.ofDays(14)));
    }

    @Test
    void aZeroInboxRetentionFailsBinding() {
        runner.withPropertyValues(REQUIRED)
                .withPropertyValues("pallet.inbox.retention=PT0S")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({OutboxProperties.class, InboxProperties.class})
    static class Enable {}
}
