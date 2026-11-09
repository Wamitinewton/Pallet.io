package io.pallet.build.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * {@code pallet.build.*}. Defaults are local-development values. Rules that span more than one key, and the rules a
 * deployed profile adds, are checked by {@link BuildPropertiesValidator}.
 */
@Validated
@ConfigurationProperties(BuildProperties.PREFIX)
public record BuildProperties(
        @Valid @NotNull @DefaultValue Execution execution,
        @Valid @NotNull @DefaultValue Images images,
        @Valid @NotNull @DefaultValue Admission admission,
        @Valid @NotNull @DefaultValue Launch launch,
        @Valid @NotNull @DefaultValue Observer observer,
        @Valid @NotNull @DefaultValue Source source,
        @Valid @NotNull @DefaultValue Registry registry,
        @Valid @NotNull @DefaultValue Logs logs,
        @Valid @NotNull @DefaultValue Retention retention,
        @Valid @NotNull @DefaultValue Observability observability,
        @DefaultValue("false") boolean deployed) {

    public static final String PREFIX = "pallet.build";

    private static final String QUANTITY = "[0-9]+(\\.[0-9]+)?(m|k|M|G|T|Ki|Mi|Gi|Ti)?";

    /**
     * At most {@code maxConcurrent} builds are in flight, none longer than {@code maxDurationCap}. Build pods run in
     * {@code namespace}, reached through {@code kubeconfig} or, when unset, the in-cluster configuration.
     */
    public record Execution(
            @Positive @DefaultValue("4") int maxConcurrent,
            @NotNull @PositiveDuration @DefaultValue("PT2H") Duration maxDurationCap,
            @NotBlank @DefaultValue("pallet-builds") String namespace,
            String kubeconfig,
            String runtimeClass,
            @NotNull @PositiveDuration @DefaultValue("PT1H") Duration ttlAfterFinished,
            @Valid @NotNull @DefaultValue Resources resources,
            @NotBlank @DefaultValue("0.14") String cnbPlatformApi,
            List<@NotBlank String> imagePullSecrets) {

        public Execution {
            imagePullSecrets = imagePullSecrets == null ? List.of() : List.copyOf(imagePullSecrets);
        }
    }

    /** The build container's requests, equal to its limits, and the pod's ephemeral storage limit. */
    public record Resources(
            @Valid @NotNull @DefaultValue ContainerResources build,

            @NotBlank @Pattern(regexp = QUANTITY) @DefaultValue("10Gi")
            String ephemeralStorage) {}

    public record ContainerResources(
            @NotBlank @Pattern(regexp = QUANTITY) @DefaultValue("2")
            String cpu,

            @NotBlank @Pattern(regexp = QUANTITY) @DefaultValue("4Gi")
            String memory) {}

    /** Every image a build pod runs. A deployed profile must pin each one by digest. */
    public record Images(
            @NotBlank @DefaultValue(
                    "pallet-registry:5000/pallet/build-steps:0.1.0@sha256:ad902a530456387fa7e179f77dd51594a8a96f648a695bf113b5cbd75832060c")
            String buildSteps,

            @NotBlank @DefaultValue(
                    "docker.io/moby/buildkit:v0.34.2-rootless@sha256:c8252268760e1baba80780d63d738ef9f260fb7c7c0973c78494cb9ddd99eda1")
            String buildkit,

            @NotBlank @DefaultValue(
                    "docker.io/buildpacksio/lifecycle:0.21.28@sha256:da6997ca53ebfa165a6433db3fefadf651327b13ecb4a896a58c4ac90a29905e")
            String lifecycle,

            @NotBlank @DefaultValue(
                    "docker.io/paketobuildpacks/ubuntu-noble-builder:0.0.217@sha256:d76f917d4a2c74e63874ca4f5e1927ef4c5c3e0c7cfbe57a36212bc02313ca2d")
            String builder,

            @NotBlank @DefaultValue(
                    "docker.io/paketobuildpacks/ubuntu-noble-run:0.0.157@sha256:b32d0ce1fdf06d9a80b2f47b3b452f7079c56ed489525f9bb0951b7beb3fbe50")
            String run) {}

    public record Admission(
            @NotNull @PositiveDuration @DefaultValue("PT1S") Duration pollInterval) {}

    /**
     * A build isn't started within {@code safetyMargin} of its {@code startBy}. Each instance leases up to
     * {@code batchSize} started builds every {@code pollInterval} for {@code claimTtl}, and gives up on one after
     * {@code maxAttempts} tries or {@code deadline} after its start, whichever comes first.
     */
    public record Launch(
            @NotNull @PositiveDuration @DefaultValue("PT10S")
            Duration safetyMargin,

            @NotNull @PositiveDuration @DefaultValue("PT1S") Duration pollInterval,
            @Positive @DefaultValue("10") int batchSize,
            @NotNull @PositiveDuration @DefaultValue("PT1M") Duration claimTtl,
            @Positive @DefaultValue("5") int maxAttempts,
            @NotNull @PositiveDuration @DefaultValue("PT5M") Duration deadline,
            @NotNull @PositiveDuration @DefaultValue("PT2S") Duration backoffInitial,

            @NotNull @PositiveDuration @DefaultValue("PT30S")
            Duration backoffMax) {}

    /**
     * Running builds' Jobs are listed in full every {@code resyncInterval}. A build whose Job is missing for
     * {@code missingGrace} while the API is reachable is lost; one past its maximum duration by {@code deadlineGrace}
     * is timed out without waiting for Kubernetes.
     */
    public record Observer(
            @NotNull @PositiveDuration @DefaultValue("PT30S")
            Duration resyncInterval,

            @NotNull @PositiveDuration @DefaultValue("PT2M") Duration missingGrace,
            @NotNull @PositiveDuration @DefaultValue("PT1M") Duration deadlineGrace) {}

    /** {@code anonymous} fetches public repositories only and is refused in a deployed profile. */
    public record Source(
            @NotNull @DefaultValue("anonymous") Credentials credentials,
            @Valid @NotNull @DefaultValue Vault vault,
            @Valid @NotNull @DefaultValue GitHub github) {

        public enum Credentials {
            VAULT,
            ANONYMOUS
        }
    }

    /** The token engine's address and mount; both are required when {@code source.credentials} is {@code vault}. */
    public record Vault(
            URI uri,
            String path,
            @NotNull @DefaultValue("token") Auth auth) {

        public enum Auth {
            TOKEN,
            KUBERNETES
        }
    }

    public record GitHub(
            @NotNull @DefaultValue("https://api.github.com") URI apiUrl) {}

    /** Build pods push to {@code host} with the credential in the {@code pushSecret} Secret of the builds namespace. */
    public record Registry(
            @NotBlank @DefaultValue("pallet-registry:5000") String host,
            @DefaultValue("true") boolean insecure,
            @NotBlank @DefaultValue("pallet-registry-push") String pushSecret) {}

    /**
     * A build's log is capped at {@code maxBytes} and stored in chunks of at most {@code chunkBytes}, flushed every
     * {@code flushInterval}. Each instance follows at most {@code maxFollowersPerInstance} streams, each under a lease
     * of {@code leaseTtl}, and stops waiting for a finished build's streams after {@code completeTimeout}.
     */
    public record Logs(
            @NotNull @DefaultValue("5MB") DataSize maxBytes,
            @NotNull @DefaultValue("16KB") DataSize chunkBytes,

            @NotNull @PositiveDuration @DefaultValue("PT0.5S")
            Duration flushInterval,

            @NotNull @PositiveDuration @DefaultValue("PT30S")
            Duration leaseTtl,

            @Positive @DefaultValue("32") int maxFollowersPerInstance,
            @NotNull @PositiveDuration @DefaultValue("PT2M") Duration completeTimeout) {}

    /**
     * Terminal builds are kept {@code builds} after they finish, dropped ones {@code dropped} after acceptance, logs
     * {@code logs} after their build finishes, and app and org tombstones {@code tombstones} after deletion. Every
     * {@code interval} each sweep deletes at most {@code maxBatchesPerRun} batches of {@code batchSize}.
     */
    public record Retention(
            @NotNull @PositiveDuration @DefaultValue("P90D") Duration builds,
            @NotNull @PositiveDuration @DefaultValue("P7D") Duration dropped,
            @NotNull @PositiveDuration @DefaultValue("P30D") Duration logs,
            @NotNull @PositiveDuration @DefaultValue("P30D") Duration tombstones,
            @Positive @DefaultValue("500") int batchSize,
            @Positive @DefaultValue("10") int maxBatchesPerRun,
            @NotNull @PositiveDuration @DefaultValue("PT1H") Duration interval) {}

    public record Observability(
            @NotNull @PositiveDuration @DefaultValue("PT15S")
            Duration gaugeInterval) {}
}
