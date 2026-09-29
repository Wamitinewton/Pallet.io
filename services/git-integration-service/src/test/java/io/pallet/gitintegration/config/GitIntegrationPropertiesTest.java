package io.pallet.gitintegration.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.UnitTest;
import io.pallet.gitintegration.access.RepoPermission;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

@UnitTest
class GitIntegrationPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Enable.class)
            .withPropertyValues(
                    "pallet.git.github.app-id=1",
                    "pallet.git.github.client-id=client",
                    "pallet.git.github.app-slug=pallet");

    @Test
    void everyDefaultBindsToTheDocumentedValue() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            GitIntegrationProperties properties = context.getBean(GitIntegrationProperties.class);
            assertThat(properties.github().apiBaseUrl()).isEqualTo(URI.create("https://api.github.com"));
            assertThat(properties.github().webBaseUrl()).isEqualTo(URI.create("https://github.com"));
            assertThat(properties.github().connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.github().readTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.github().webhookSecrets()).isEmpty();
            assertThat(properties.authorization().stateTtl()).isEqualTo(Duration.ofMinutes(10));
            assertThat(properties.userSession().maxTtl()).isEqualTo(Duration.ofHours(1));
            assertThat(properties.link().minRepoPermission()).isEqualTo(RepoPermission.PUSH);
            assertThat(properties.reverify().interval()).isEqualTo(Duration.ofDays(1));
            assertThat(properties.reverify().maxPerRun()).isEqualTo(5000);
            assertThat(properties.unusedInstallation().grace()).isEqualTo(Duration.ofDays(30));
            assertThat(properties.unusedInstallation().enabled()).isTrue();
            assertThat(properties.unusedInstallation().sweepInterval()).isEqualTo(Duration.ofDays(1));
            assertThat(properties.unusedInstallation().maxPerRun()).isEqualTo(100);
            assertThat(properties.unusedInstallation().metricsInterval()).isEqualTo(Duration.ofMinutes(1));
            assertThat(properties.manualBuildsPerHour()).isEqualTo(30);
            assertThat(properties.webhook().maxBody()).isEqualTo(DataSize.ofMegabytes(25));
            assertThat(properties.webhook().githubIpAllowlist().enabled()).isFalse();
            assertThat(properties.delivery().pollInterval()).isEqualTo(Duration.ofMillis(250));
            assertThat(properties.delivery().batchSize()).isEqualTo(50);
            assertThat(properties.delivery().maxAttempts()).isEqualTo(10);
            assertThat(properties.delivery().enabled()).isTrue();
            assertThat(properties.delivery().workers()).isEqualTo(1);
            assertThat(properties.delivery().lease()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.delivery().maxLookupRounds()).isEqualTo(3);
            assertThat(properties.delivery().backoffBase()).isEqualTo(Duration.ofSeconds(1));
            assertThat(properties.delivery().backoffMax()).isEqualTo(Duration.ofMinutes(10));
            assertThat(properties.delivery().unavailableBackoffMax()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.redelivery().interval()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.redelivery().lookback()).isEqualTo(Duration.ofHours(24));
            assertThat(properties.redelivery().maxPerRun()).isEqualTo(100);
            assertThat(properties.redelivery().enabled()).isTrue();
            assertThat(properties.redelivery().overlap()).isEqualTo(Duration.ofMinutes(10));
            assertThat(properties.redelivery().maxPages()).isEqualTo(50);
            assertThat(properties.reconciler().enabled()).isTrue();
            assertThat(properties.reconciler().interval()).isEqualTo(Duration.ofMinutes(15));
            assertThat(properties.reconciler().maxPerRun()).isEqualTo(500);
            assertThat(properties.ratelimit().reserve()).isEqualTo(500);
            assertThat(properties.tokens().refreshSkew()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.tokens().maxCached()).isEqualTo(10_000);
            assertThat(properties.push().skipMarkers()).containsExactly("[skip ci]", "[ci skip]", "[pallet skip]");
            assertThat(properties.checks().enabled()).isTrue();
            assertThat(properties.checks().pollInterval()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.checks().batchSize()).isEqualTo(50);
            assertThat(properties.checks().lease()).isEqualTo(Duration.ofMinutes(2));
            assertThat(properties.checks().parallelism()).isEqualTo(4);
            assertThat(properties.checks().maxAttempts()).isEqualTo(20);
            assertThat(properties.checks().awaitDeploy()).isFalse();
            assertThat(properties.retention().deliveryPayload()).isEqualTo(Duration.ofDays(7));
            assertThat(properties.retention().deliveries()).isEqualTo(Duration.ofDays(30));
            assertThat(properties.retention().authorizationStates()).isEqualTo(Duration.ofDays(1));
            assertThat(properties.retention().terminalConnections()).isEqualTo(Duration.ofDays(90));
            assertThat(properties.retention().enabled()).isTrue();
            assertThat(properties.retention().payloadSweepInterval()).isEqualTo(Duration.ofHours(1));
            assertThat(properties.retention().sweepInterval()).isEqualTo(Duration.ofDays(1));
            assertThat(properties.retention().batchSize()).isEqualTo(1000);
            assertThat(properties.retention().maxBatchesPerRun()).isEqualTo(1000);
            assertThat(properties.retention().manualBuildRequests()).isEqualTo(Duration.ofDays(7));
            assertThat(properties.retention().checkRuns()).isEqualTo(Duration.ofDays(30));
            assertThat(properties.retention().deletedApps()).isEqualTo(Duration.ofDays(90));
            assertThat(properties.retention().deletedOrgs()).isEqualTo(Duration.ofDays(90));
        });
    }

    @Test
    void theMinimumPermissionBindsFromItsLowercaseGitHubName() {
        runner.withPropertyValues("pallet.git.link.min-repo-permission=maintain")
                .run(context -> assertThat(context.getBean(GitIntegrationProperties.class)
                                .link()
                                .minRepoPermission())
                        .isEqualTo(RepoPermission.MAINTAIN));
    }

    @Test
    void aZeroPollIntervalFailsValidation() {
        runner.withPropertyValues("pallet.git.delivery.poll-interval=PT0S")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aNegativeGraceFailsValidation() {
        runner.withPropertyValues("pallet.git.unused-installation.grace=-P1D")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aZeroBatchSizeFailsValidation() {
        runner.withPropertyValues("pallet.git.delivery.batch-size=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aWebhookBodyCapAboveTheCeilingFailsValidation() {
        runner.withPropertyValues("pallet.git.webhook.max-body=101MB")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void anEmptySkipMarkerListIsAllowed() {
        runner.withPropertyValues("pallet.git.push.skip-markers=").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(GitIntegrationProperties.class).push().skipMarkers())
                    .isEmpty();
        });
    }

    @Test
    void aMissingAppIdentityFailsValidation() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(Enable.class)
                .withPropertyValues("pallet.git.github.client-id=client", "pallet.git.github.app-slug=pallet")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theSecretsNeverAppearInToString() {
        runner.withPropertyValues(
                        "pallet.git.github.private-key=PEM-BODY",
                        "pallet.git.github.client-secret=CLIENT-SECRET",
                        "pallet.git.github.webhook-secrets[0]=WEBHOOK-SECRET",
                        "pallet.git.authorization.state-signing-key=STATE-KEY",
                        "pallet.git.user-session.encryption-key=SESSION-KEY")
                .run(context -> assertThat(
                                context.getBean(GitIntegrationProperties.class).toString())
                        .doesNotContain("PEM-BODY", "CLIENT-SECRET", "WEBHOOK-SECRET", "STATE-KEY", "SESSION-KEY"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GitIntegrationProperties.class)
    static class Enable {}
}
