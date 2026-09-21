package io.pallet.orgteam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.annotations.UnitTest;
import io.pallet.orgteam.config.OrgTeamProperties;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@UnitTest
class OrgTeamPropertiesTest {

    @Test
    void defaultsBindWhenNothingIsConfigured() {
        OrgTeamProperties properties = bind(Map.of());

        assertThat(properties.invites().ttl()).isEqualTo(Duration.ofHours(72));
        assertThat(properties.invites().maxPendingPerOrg()).isEqualTo(50);
        assertThat(properties.invites().maxSends()).isEqualTo(4);
        assertThat(properties.limits().maxAppsPerOrg()).isEqualTo(200);
        assertThat(properties.security().recentAuthWindow()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.outbox().pollInterval()).isEqualTo(Duration.ofMillis(250));
        assertThat(properties.outbox().batchSize()).isEqualTo(100);
        assertThat(properties.outbox().maxAttempts()).isEqualTo(10);
        assertThat(properties.outbox().retention()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.inbox().retention()).isEqualTo(Duration.ofDays(14));
        assertThat(properties.retention().removedMemberships()).isEqualTo(Duration.ofDays(365));
        assertThat(properties.retention().enabled()).isTrue();
        assertThat(properties.retention().inviteExpiryInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.retention().dailySweepInterval()).isEqualTo(Duration.ofDays(1));
        assertThat(properties.retention().orgPurgeBatchSize()).isEqualTo(25);
        assertThat(properties.apps().regions().aws()).contains("us-east-1");
        assertThat(properties.apps().regions().gcp()).contains("us-central1");
    }

    @Test
    void aZeroPollIntervalFailsValidation() {
        assertBindFails("pallet.orgteam.outbox.poll-interval", "PT0S");
    }

    @Test
    void aNegativeInviteTtlFailsValidation() {
        assertBindFails("pallet.orgteam.invites.ttl", "-PT1H");
    }

    @Test
    void aZeroBatchSizeFailsValidation() {
        assertBindFails("pallet.orgteam.outbox.batch-size", "0");
    }

    @Test
    void aZeroRetentionWindowFailsValidation() {
        assertBindFails("pallet.orgteam.retention.deleted-orgs", "PT0S");
    }

    private void assertBindFails(String key, String value) {
        assertThatThrownBy(() -> bind(Map.of(key, value))).hasRootCauseInstanceOf(BindValidationException.class);
    }

    private OrgTeamProperties bind(Map<String, String> source) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return new Binder(new MapConfigurationPropertySource(source))
                .bindOrCreate(
                        "pallet.orgteam", Bindable.of(OrgTeamProperties.class), new ValidationBindHandler(validator));
    }
}
