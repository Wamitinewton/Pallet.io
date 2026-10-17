package io.pallet.buildqueue.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.buildqueue.config.BuildQueueProperties.Profile;
import io.pallet.common.test.annotations.UnitTest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@UnitTest
class BuildQueuePropertiesValidatorTest {

    private static final String PREFIX = BuildQueueProperties.PREFIX + ".";

    @Test
    void theDefaultsBindToTheDocumentedValuesAndPass() {
        BuildQueueProperties properties = bind(Map.of());

        assertThat(properties.dispatch().globalConcurrency()).isEqualTo(4);
        assertThat(properties.dispatch().batchSize()).isEqualTo(50);
        assertThat(properties.dispatch().pollInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.dispatch().startTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.dispatch().maxAttempts()).isEqualTo(3);
        assertThat(properties.dispatch().paused()).isFalse();
        assertThat(properties.dispatch().wakeupEnabled()).isTrue();
        assertThat(properties.build().maxDuration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.lease().grace()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.lease().sweepInterval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.lease().reachabilityTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.lease().batchSize()).isEqualTo(100);
        assertThat(properties.lease().sweeperEnabled()).isTrue();
        assertThat(properties.queue().maxAge()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.limits().defaultProfile()).isEqualTo("default");
        assertThat(properties.limits().profiles())
                .containsExactlyInAnyOrderEntriesOf(Map.of("default", new Profile(1, 50), "team", new Profile(3, 200)));
        assertThat(properties.limits().orgProfiles()).isEmpty();
        assertThat(properties.retention().terminalBuilds()).isEqualTo(Duration.ofDays(30));
        assertThat(properties.retention().tombstones()).isEqualTo(Duration.ofDays(30));
        assertThat(properties.retention().batchSize()).isEqualTo(500);
        assertThat(properties.retention().maxBatchesPerRun()).isEqualTo(10);
        assertThat(properties.retention().interval()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.retention().enabled()).isTrue();
        assertThat(properties.observability().gaugeInterval()).isEqualTo(Duration.ofSeconds(15));

        assertThatCode(() -> BuildQueuePropertiesValidator.validate(properties)).doesNotThrowAnyException();
    }

    @Test
    void configuredProfilesReplaceTheBuiltInOnesAndOrgOverridesBind() {
        BuildQueueProperties properties = bind(Map.of(
                "limits.default-profile", "free",
                "limits.profiles.free.max-concurrent", "2",
                "limits.profiles.free.max-queued", "10",
                "limits.org-profiles[org-1]", "free"));

        assertThat(properties.limits().profiles()).containsOnlyKeys("free");
        assertThat(properties.limits().orgProfiles()).containsExactlyEntriesOf(Map.of("org-1", "free"));
        assertThatCode(() -> BuildQueuePropertiesValidator.validate(properties)).doesNotThrowAnyException();
    }

    @Test
    void aDefaultProfileThatNamesNoProfileIsRejected() {
        assertRejected(Map.of("limits.default-profile", "nope"), BuildQueuePropertiesValidator.DEFAULT_PROFILE);
    }

    @Test
    void anOrgOverrideThatNamesAnUnknownProfileIsRejected() {
        assertRejected(
                Map.of("limits.org-profiles[org-1]", "enterprise"),
                BuildQueuePropertiesValidator.ORG_PROFILES + ".org-1");
    }

    @Test
    void aProfileWithoutConcurrencyIsRejected() {
        assertRejected(
                Map.of("limits.profiles.team.max-concurrent", "0", "limits.profiles.team.max-queued", "200"),
                BuildQueuePropertiesValidator.PROFILES + ".team.max-concurrent");
    }

    @Test
    void aProfileWithoutQueueRoomIsRejected() {
        assertRejected(
                Map.of("limits.profiles.default.max-concurrent", "1", "limits.profiles.default.max-queued", "0"),
                BuildQueuePropertiesValidator.PROFILES + ".default.max-queued");
    }

    @Test
    void aStartTimeoutNoLongerThanTheTimeToNoticeItIsRejected() {
        assertRejected(
                Map.of(
                        "dispatch.poll-interval", "PT1S",
                        "lease.sweep-interval", "PT14S",
                        "dispatch.start-timeout", "PT15S"),
                BuildQueuePropertiesValidator.START_TIMEOUT);
    }

    @Test
    void aStartTimeoutJustLongerThanTheTimeToNoticeItPasses() {
        BuildQueueProperties properties = bind(Map.of(
                "dispatch.poll-interval", "PT1S",
                "lease.sweep-interval", "PT14S",
                "dispatch.start-timeout", "PT16S"));

        assertThatCode(() -> BuildQueuePropertiesValidator.validate(properties)).doesNotThrowAnyException();
    }

    @Test
    void aZeroLeaseGraceIsRejected() {
        assertRejected(Map.of("lease.grace", "PT0S"), BuildQueuePropertiesValidator.LEASE_GRACE);
    }

    @Test
    void aNegativeLeaseGraceIsRejected() {
        assertRejected(Map.of("lease.grace", "-PT1M"), BuildQueuePropertiesValidator.LEASE_GRACE);
    }

    @Test
    void zeroDispatchAttemptsIsRejected() {
        assertRejected(Map.of("dispatch.max-attempts", "0"), BuildQueuePropertiesValidator.MAX_ATTEMPTS);
    }

    @Test
    void tombstonesThatDieBeforeTheirPushesAreRejected() {
        assertRejected(Map.of("retention.tombstones", "P7D"), BuildQueuePropertiesValidator.TOMBSTONES);
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        assertThatThrownBy(() -> BuildQueuePropertiesValidator.validate(
                        bind(Map.of("limits.default-profile", "nope", "dispatch.max-attempts", "0"))))
                .hasMessageContaining(BuildQueuePropertiesValidator.DEFAULT_PROFILE)
                .hasMessageContaining(BuildQueuePropertiesValidator.MAX_ATTEMPTS);
    }

    @Test
    void theContextRefusesToStartWithAnInconsistentConfiguration() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(Wiring.class);

        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(BuildQueuePropertiesValidator.class));

        runner.withPropertyValues(PREFIX + "limits.default-profile=nope").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(BuildQueuePropertiesValidator.DEFAULT_PROFILE);
        });
    }

    private static void assertRejected(Map<String, String> values, String key) {
        BuildQueueProperties properties = bind(values);

        assertThatThrownBy(() -> BuildQueuePropertiesValidator.validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(key);
    }

    private static BuildQueueProperties bind(Map<String, String> values) {
        Map<String, String> prefixed = new HashMap<>();
        values.forEach((key, value) -> prefixed.put(PREFIX + key, value));
        return new Binder(new MapConfigurationPropertySource(prefixed))
                .bindOrCreate(BuildQueueProperties.PREFIX, BuildQueueProperties.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BuildQueueProperties.class)
    @Import(BuildQueuePropertiesValidator.class)
    static class Wiring {}
}
