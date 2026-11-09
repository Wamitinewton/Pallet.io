package io.pallet.build.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.build.config.BuildProperties.Source;
import io.pallet.build.config.BuildProperties.Vault;
import io.pallet.common.test.annotations.UnitTest;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.util.unit.DataSize;

@UnitTest
class BuildPropertiesValidatorTest {

    private static final String PREFIX = BuildProperties.PREFIX + ".";
    private static final String DIGEST = "@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final List<String> IMAGES = List.of("build-steps", "buildkit", "lifecycle", "builder", "run");

    @Test
    void theDefaultsBindToTheDocumentedValuesAndPass() {
        BuildProperties properties = bind(Map.of());

        assertThat(properties.execution().maxConcurrent()).isEqualTo(4);
        assertThat(properties.execution().maxDurationCap()).isEqualTo(Duration.ofHours(2));
        assertThat(properties.execution().namespace()).isEqualTo("pallet-builds");
        assertThat(properties.execution().kubeconfig()).isNull();
        assertThat(properties.execution().runtimeClass()).isNull();
        assertThat(properties.execution().ttlAfterFinished()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.execution().resources().build().cpu()).isEqualTo("2");
        assertThat(properties.execution().resources().build().memory()).isEqualTo("4Gi");
        assertThat(properties.execution().resources().ephemeralStorage()).isEqualTo("10Gi");
        assertThat(properties.execution().cnbPlatformApi()).isEqualTo("0.14");
        assertThat(properties.execution().imagePullSecrets()).isEmpty();
        assertThat(properties.admission().pollInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.launch().safetyMargin()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.launch().pollInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.launch().batchSize()).isEqualTo(10);
        assertThat(properties.launch().claimTtl()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.launch().maxAttempts()).isEqualTo(5);
        assertThat(properties.launch().deadline()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.launch().backoffInitial()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.launch().backoffMax()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.observer().resyncInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.observer().missingGrace()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.observer().deadlineGrace()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.source().credentials()).isEqualTo(Source.Credentials.ANONYMOUS);
        assertThat(properties.source().vault().auth()).isEqualTo(Vault.Auth.TOKEN);
        assertThat(properties.source().github().apiUrl()).isEqualTo(URI.create("https://api.github.com"));
        assertThat(properties.registry().host()).isEqualTo("pallet-registry:5000");
        assertThat(properties.registry().insecure()).isTrue();
        assertThat(properties.registry().pushSecret()).isEqualTo("pallet-registry-push");
        assertThat(properties.logs().maxBytes()).isEqualTo(DataSize.ofMegabytes(5));
        assertThat(properties.logs().chunkBytes()).isEqualTo(DataSize.ofKilobytes(16));
        assertThat(properties.logs().flushInterval()).isEqualTo(Duration.ofMillis(500));
        assertThat(properties.logs().leaseTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.logs().maxFollowersPerInstance()).isEqualTo(32);
        assertThat(properties.logs().completeTimeout()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.retention().builds()).isEqualTo(Duration.ofDays(90));
        assertThat(properties.retention().dropped()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.retention().logs()).isEqualTo(Duration.ofDays(30));
        assertThat(properties.retention().tombstones()).isEqualTo(Duration.ofDays(30));
        assertThat(properties.retention().batchSize()).isEqualTo(500);
        assertThat(properties.retention().maxBatchesPerRun()).isEqualTo(10);
        assertThat(properties.retention().interval()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.observability().gaugeInterval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.deployed()).isFalse();

        assertThatCode(() -> BuildPropertiesValidator.validate(properties)).doesNotThrowAnyException();
    }

    @Test
    void theLocalConfigurationWithTaggedImagesPasses() {
        assertThatCode(() -> BuildPropertiesValidator.validate(bind(localConfiguration())))
                .doesNotThrowAnyException();
    }

    @Test
    void aDeployedConfigurationWithDigestsVaultAndARuntimeClassPasses() {
        assertThatCode(() -> BuildPropertiesValidator.validate(bind(deployedConfiguration())))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "-PT1S"})
    void aSafetyMarginThatIsNotPositiveIsRejected(String margin) {
        assertRejected(Map.of("launch.safety-margin", margin), BuildPropertiesValidator.SAFETY_MARGIN);
    }

    @Test
    void aSafetyMarginNoShorterThanTheLaunchDeadlineIsRejected() {
        assertRejected(
                Map.of("launch.safety-margin", "PT5M", "launch.deadline", "PT5M"),
                BuildPropertiesValidator.SAFETY_MARGIN);
    }

    @Test
    void aLaunchDeadlineShorterThanTheClaimTtlIsRejected() {
        assertRejected(
                Map.of("launch.deadline", "PT50S", "launch.claim-ttl", "PT1M"),
                BuildPropertiesValidator.LAUNCH_DEADLINE);
    }

    @Test
    void aLaunchDeadlineEqualToTheClaimTtlPasses() {
        assertThatCode(() -> BuildPropertiesValidator.validate(
                        bind(Map.of("launch.deadline", "PT1M", "launch.claim-ttl", "PT1M"))))
                .doesNotThrowAnyException();
    }

    @Test
    void aBackoffCeilingBelowItsStartIsRejected() {
        assertRejected(
                Map.of("launch.backoff-initial", "PT10S", "launch.backoff-max", "PT5S"),
                BuildPropertiesValidator.BACKOFF_MAX);
    }

    @Test
    void aLogCapAboveEightMegabytesIsRejected() {
        assertRejected(Map.of("logs.max-bytes", "9MB"), BuildPropertiesValidator.LOG_MAX_BYTES);
    }

    @Test
    void aLogCapOfExactlyEightMegabytesPasses() {
        assertThatCode(() -> BuildPropertiesValidator.validate(bind(Map.of("logs.max-bytes", "8MB"))))
                .doesNotThrowAnyException();
    }

    @Test
    void aZeroLogCapIsRejected() {
        assertRejected(Map.of("logs.max-bytes", "0B"), BuildPropertiesValidator.LOG_MAX_BYTES);
    }

    @Test
    void aChunkLargerThanTheLogCapIsRejected() {
        assertRejected(
                Map.of("logs.max-bytes", "1MB", "logs.chunk-bytes", "2MB"), BuildPropertiesValidator.LOG_CHUNK_BYTES);
    }

    @Test
    void anEmptyChunkIsRejected() {
        assertRejected(Map.of("logs.chunk-bytes", "0B"), BuildPropertiesValidator.LOG_CHUNK_BYTES);
    }

    @Test
    void vaultCredentialsWithoutAnAddressOrMountAreRejected() {
        assertThatThrownBy(() -> BuildPropertiesValidator.validate(bind(Map.of("source.credentials", "vault"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BuildPropertiesValidator.VAULT_URI)
                .hasMessageContaining(BuildPropertiesValidator.VAULT_PATH);
    }

    @Test
    void aDeployedProfileWithAnonymousCredentialsIsRejected() {
        Map<String, String> values = deployedConfiguration();
        values.put("source.credentials", "anonymous");

        assertRejected(values, BuildPropertiesValidator.CREDENTIALS);
    }

    @Test
    void aDeployedProfileWithAnInsecureRegistryIsRejected() {
        Map<String, String> values = deployedConfiguration();
        values.put("registry.insecure", "true");

        assertRejected(values, BuildPropertiesValidator.REGISTRY_INSECURE);
    }

    @Test
    void aDeployedProfileWithoutARuntimeClassIsRejected() {
        Map<String, String> values = deployedConfiguration();
        values.remove("execution.runtime-class");

        assertRejected(values, BuildPropertiesValidator.RUNTIME_CLASS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"build-steps", "buildkit", "lifecycle", "builder", "run"})
    void aDeployedProfileWithAnImageNotPinnedByDigestIsRejected(String image) {
        Map<String, String> values = deployedConfiguration();
        values.put("images." + image, "docker.io/acme/" + image + ":1.0.0");

        assertRejected(values, BuildPropertiesValidator.IMAGES + "." + image);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "docker.io/acme/tool",
                "docker.io/acme/tool@sha256:abc",
                "Docker.io/Acme/Tool:1.0",
                "docker.io/acme/tool:",
                "docker.io//tool:1.0",
                " "
            })
    void anImageReferenceThatIsMalformedOrUnversionedIsRejected(String reference) {
        assertRejected(Map.of("images.buildkit", reference), BuildPropertiesValidator.IMAGES + ".buildkit");
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost:5001/pallet/build-steps:0.1.0", "busybox:1.37", "acme/tool" + DIGEST})
    void aTaggedOrDigestPinnedImageIsAcceptedLocally(String reference) {
        assertThatCode(() -> BuildPropertiesValidator.validate(bind(Map.of("images.buildkit", reference))))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"", "pallet registry:5000", "registry:0", "registry:65536", "-registry", "https://registry"})
    void aRegistryHostThatIsNotHostAndPortIsRejected(String host) {
        assertRejected(Map.of("registry.host", host), BuildPropertiesValidator.REGISTRY_HOST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"registry.example.com", "pallet-registry:5000", "10.0.0.4:443", "localhost"})
    void aRegistryHostWithAnOptionalPortPasses(String host) {
        assertThatCode(() -> BuildPropertiesValidator.validate(bind(Map.of("registry.host", host))))
                .doesNotThrowAnyException();
    }

    @Test
    void tombstonesThatDieBeforeTheirDispatchesAreRejected() {
        assertRejected(Map.of("retention.tombstones", "P7D"), BuildPropertiesValidator.TOMBSTONES);
    }

    @Test
    void tombstonesKeptEightDaysPass() {
        assertThatCode(() -> BuildPropertiesValidator.validate(bind(Map.of("retention.tombstones", "P8D"))))
                .doesNotThrowAnyException();
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        assertThatThrownBy(() -> BuildPropertiesValidator.validate(
                        bind(Map.of("retention.tombstones", "P1D", "logs.max-bytes", "64MB"))))
                .hasMessageContaining(BuildPropertiesValidator.TOMBSTONES)
                .hasMessageContaining(BuildPropertiesValidator.LOG_MAX_BYTES);
    }

    @Test
    void theContextRefusesToStartWhenDeployedWithTheLocalDefaults() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(Wiring.class);

        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(BuildPropertiesValidator.class));

        runner.withPropertyValues(PREFIX + "deployed=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(BuildPropertiesValidator.CREDENTIALS);
        });
    }

    @Test
    void theContextRefusesANonPositiveDurationBeforeTheValidatorRuns() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(Wiring.class)
                .withPropertyValues(PREFIX + "observer.missing-grace=PT0S")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("missingGrace");
                });
    }

    private static Map<String, String> localConfiguration() {
        Map<String, String> values = new HashMap<>();
        values.put("images.build-steps", "pallet-registry:5000/pallet/build-steps:0.1.0");
        values.put("images.buildkit", "docker.io/moby/buildkit:v0.34.2-rootless");
        values.put("images.lifecycle", "docker.io/buildpacksio/lifecycle:0.21.28");
        values.put("images.builder", "docker.io/paketobuildpacks/ubuntu-noble-builder:0.0.217");
        values.put("images.run", "docker.io/paketobuildpacks/ubuntu-noble-run:0.0.157");
        values.put("execution.kubeconfig", "../../deploy/local/build-service.kubeconfig");
        values.put("source.credentials", "anonymous");
        values.put("registry.insecure", "true");
        return values;
    }

    private static Map<String, String> deployedConfiguration() {
        Map<String, String> values = new HashMap<>();
        values.put("deployed", "true");
        values.put("execution.runtime-class", "gvisor");
        values.put("source.credentials", "vault");
        values.put("source.vault.uri", "https://vault.pallet.internal:8200");
        values.put("source.vault.path", "github");
        values.put("source.vault.auth", "kubernetes");
        values.put("registry.host", "registry.pallet.internal");
        values.put("registry.insecure", "false");
        IMAGES.forEach(image -> values.put("images." + image, "registry.pallet.internal/pallet/" + image + DIGEST));
        return values;
    }

    private static void assertRejected(Map<String, String> values, String key) {
        BuildProperties properties = bind(values);

        assertThatThrownBy(() -> BuildPropertiesValidator.validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(key);
    }

    private static BuildProperties bind(Map<String, String> values) {
        Map<String, String> prefixed = new HashMap<>();
        values.forEach((key, value) -> prefixed.put(PREFIX + key, value));
        return new Binder(new MapConfigurationPropertySource(prefixed))
                .bindOrCreate(BuildProperties.PREFIX, BuildProperties.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BuildProperties.class)
    @Import(BuildPropertiesValidator.class)
    static class Wiring {}
}
