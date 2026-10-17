package io.pallet.buildqueue.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.buildqueue.config.BuildQueueProperties;
import io.pallet.buildqueue.config.BuildQueueProperties.Profile;
import io.pallet.buildqueue.config.BuildQueuePropertiesValidator;
import io.pallet.buildqueue.dispatch.LimitProfiles.OverrideRow;
import io.pallet.buildqueue.dispatch.LimitProfiles.ProfileRow;
import io.pallet.common.test.annotations.UnitTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@UnitTest
class LimitProfilesTest {

    private static final String LIMITS = BuildQueueProperties.PREFIX + ".limits.";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Wiring.class)
            .withPropertyValues(
                    LIMITS + "default-profile=free",
                    LIMITS + "profiles.free.max-concurrent=1",
                    LIMITS + "profiles.free.max-queued=10",
                    LIMITS + "profiles.pro.max-concurrent=5",
                    LIMITS + "profiles.pro.max-queued=100",
                    LIMITS + "org-profiles[org-pro]=pro");

    @Test
    void anOrgWithAnOverrideGetsItsProfile() {
        runner.run(context -> {
            LimitProfiles limits = context.getBean(LimitProfiles.class);

            assertThat(limits.profileName("org-pro")).isEqualTo("pro");
            assertThat(limits.forOrg("org-pro")).isEqualTo(new Profile(5, 100));
        });
    }

    @Test
    void anOrgWithoutAnOverrideGetsTheDefaultProfile() {
        runner.run(context -> {
            LimitProfiles limits = context.getBean(LimitProfiles.class);

            assertThat(limits.profileName("org-anyone")).isEqualTo("free");
            assertThat(limits.forOrg("org-anyone")).isEqualTo(new Profile(1, 10));
            assertThat(limits.defaultProfileName()).isEqualTo("free");
        });
    }

    @Test
    void theQueryRowsListEveryProfileAndOverrideInNameOrder() {
        runner.withPropertyValues(LIMITS + "org-profiles[org-a]=free").run(context -> {
            LimitProfiles limits = context.getBean(LimitProfiles.class);

            assertThat(limits.asValuesRows()).containsExactly(new ProfileRow("free", 1), new ProfileRow("pro", 5));
            assertThat(limits.overrideRows())
                    .containsExactly(new OverrideRow("org-a", "free"), new OverrideRow("org-pro", "pro"));
        });
    }

    @Test
    void anOverrideNamingAnUnknownProfileStopsTheServiceFromStarting() {
        runner.withPropertyValues(LIMITS + "org-profiles[org-x]=enterprise").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(LIMITS + "org-profiles.org-x")
                    .hasMessageContaining("enterprise");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BuildQueueProperties.class)
    @Import({BuildQueuePropertiesValidator.class, LimitProfiles.class})
    static class Wiring {}
}
