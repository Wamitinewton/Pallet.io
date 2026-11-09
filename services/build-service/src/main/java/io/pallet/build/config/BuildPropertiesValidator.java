package io.pallet.build.config;

import io.pallet.build.config.BuildProperties.Images;
import io.pallet.build.config.BuildProperties.Launch;
import io.pallet.build.config.BuildProperties.Logs;
import io.pallet.build.config.BuildProperties.Source;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

/** Refuses to start the service with a configuration that is inconsistent or unsafe. Every failure names its key. */
@Component
public class BuildPropertiesValidator {

    static final String SAFETY_MARGIN = key("launch.safety-margin");
    static final String LAUNCH_DEADLINE = key("launch.deadline");
    static final String CLAIM_TTL = key("launch.claim-ttl");
    static final String BACKOFF_INITIAL = key("launch.backoff-initial");
    static final String BACKOFF_MAX = key("launch.backoff-max");
    static final String LOG_MAX_BYTES = key("logs.max-bytes");
    static final String LOG_CHUNK_BYTES = key("logs.chunk-bytes");
    static final String CREDENTIALS = key("source.credentials");
    static final String VAULT_URI = key("source.vault.uri");
    static final String VAULT_PATH = key("source.vault.path");
    static final String REGISTRY_HOST = key("registry.host");
    static final String REGISTRY_INSECURE = key("registry.insecure");
    static final String RUNTIME_CLASS = key("execution.runtime-class");
    static final String IMAGES = key("images");
    static final String TOMBSTONES = key("retention.tombstones");

    /** Log capture resumes a stream by byte offset, which only works while the log stays below the kubelet's rotation. */
    static final DataSize MAX_LOG_BYTES = DataSize.ofMegabytes(8);

    /** A tombstone has to outlive {@code build.dispatched}'s seven-day retention, or a buffered dispatch revives an app. */
    static final Duration MIN_TOMBSTONE_RETENTION = Duration.ofDays(8);

    private static final String COMPONENT = "[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*";
    private static final String LABEL = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?";
    private static final String HOST = LABEL + "(?:\\." + LABEL + ")*";
    private static final String PORT = "[0-9]{1,5}";
    private static final String DIGEST = "sha256:[0-9a-f]{64}";

    private static final Pattern REGISTRY_HOST_FORM = Pattern.compile("(" + HOST + ")(?::(" + PORT + "))?");
    private static final Pattern IMAGE_FORM = Pattern.compile("(?:" + HOST
            + "(?::" + PORT + ")?/)?" + COMPONENT + "(?:/" + COMPONENT + ")*"
            + "(?<tag>:[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})?(?<digest>@" + DIGEST + ")?");

    BuildPropertiesValidator(BuildProperties properties) {
        validate(properties);
    }

    static void validate(BuildProperties properties) {
        List<String> problems = new ArrayList<>();
        checkLaunch(properties.launch(), problems);
        checkLogs(properties.logs(), problems);
        checkSource(properties.source(), problems);
        checkRegistryHost(properties.registry().host(), problems);
        checkImages(properties.images(), properties.deployed(), problems);
        if (properties.deployed()) {
            checkDeployed(properties, problems);
        }
        if (properties.retention().tombstones().compareTo(MIN_TOMBSTONE_RETENTION) < 0) {
            problems.add(TOMBSTONES + " must be at least " + MIN_TOMBSTONE_RETENTION
                    + ": a tombstone has to outlive build.dispatched's retention, or a dispatch buffered for a deleted"
                    + " app finds no tombstone and brings the app back");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Invalid " + BuildProperties.PREFIX + " configuration: " + String.join("; ", problems));
        }
    }

    private static void checkLaunch(Launch launch, List<String> problems) {
        if (!launch.safetyMargin().isPositive()) {
            problems.add(SAFETY_MARGIN + " must be positive");
        } else if (launch.safetyMargin().compareTo(launch.deadline()) >= 0) {
            problems.add(SAFETY_MARGIN + " must be shorter than " + LAUNCH_DEADLINE);
        }
        if (launch.deadline().compareTo(launch.claimTtl()) < 0) {
            problems.add(LAUNCH_DEADLINE + " must not be shorter than " + CLAIM_TTL
                    + ", or a launch gives up before its first lease can expire");
        }
        if (launch.backoffMax().compareTo(launch.backoffInitial()) < 0) {
            problems.add(BACKOFF_MAX + " must not be shorter than " + BACKOFF_INITIAL);
        }
    }

    private static void checkLogs(Logs logs, List<String> problems) {
        if (!isPositive(logs.maxBytes())) {
            problems.add(LOG_MAX_BYTES + " must be positive");
        } else if (logs.maxBytes().compareTo(MAX_LOG_BYTES) > 0) {
            problems.add(LOG_MAX_BYTES + " must be at most " + MAX_LOG_BYTES.toMegabytes() + "MB, below the kubelet's"
                    + " log rotation, or a resumed stream can miss what rotated away");
        }
        if (!isPositive(logs.chunkBytes())) {
            problems.add(LOG_CHUNK_BYTES + " must be positive");
        } else if (logs.chunkBytes().compareTo(logs.maxBytes()) > 0) {
            problems.add(LOG_CHUNK_BYTES + " must not be larger than " + LOG_MAX_BYTES);
        }
    }

    private static void checkSource(Source source, List<String> problems) {
        if (source.credentials() != Source.Credentials.VAULT) {
            return;
        }
        if (source.vault().uri() == null) {
            problems.add(VAULT_URI + " is required when " + CREDENTIALS + " is vault");
        }
        if (!StringUtils.hasText(source.vault().path())) {
            problems.add(VAULT_PATH + " is required when " + CREDENTIALS + " is vault");
        }
    }

    private static void checkRegistryHost(String host, List<String> problems) {
        Matcher matcher = REGISTRY_HOST_FORM.matcher(host == null ? "" : host);
        if (!matcher.matches() || !validPort(matcher.group(2))) {
            problems.add(REGISTRY_HOST + " must be host[:port]");
        }
    }

    private static void checkImages(Images images, boolean deployed, List<String> problems) {
        imagesByKey(images).forEach((key, reference) -> {
            Matcher matcher = IMAGE_FORM.matcher(reference == null ? "" : reference);
            if (!matcher.matches() || (matcher.group("tag") == null && matcher.group("digest") == null)) {
                problems.add(key + " must be an image reference with a tag or a digest");
            } else if (deployed && matcher.group("digest") == null) {
                problems.add(key + " must be pinned by digest (@sha256:...) in a deployed profile");
            }
        });
    }

    private static void checkDeployed(BuildProperties properties, List<String> problems) {
        if (properties.source().credentials() == Source.Credentials.ANONYMOUS) {
            problems.add(CREDENTIALS + " must not be anonymous in a deployed profile");
        }
        if (properties.registry().insecure()) {
            problems.add(REGISTRY_INSECURE + " must be false in a deployed profile");
        }
        if (!StringUtils.hasText(properties.execution().runtimeClass())) {
            problems.add(RUNTIME_CLASS + " is required in a deployed profile");
        }
    }

    private static Map<String, String> imagesByKey(Images images) {
        Map<String, String> byKey = new LinkedHashMap<>();
        byKey.put(IMAGES + ".build-steps", images.buildSteps());
        byKey.put(IMAGES + ".buildkit", images.buildkit());
        byKey.put(IMAGES + ".lifecycle", images.lifecycle());
        byKey.put(IMAGES + ".builder", images.builder());
        byKey.put(IMAGES + ".run", images.run());
        return byKey;
    }

    private static boolean validPort(String port) {
        if (port == null) {
            return true;
        }
        int value = Integer.parseInt(port);
        return value >= 1 && value <= 65_535;
    }

    private static boolean isPositive(DataSize size) {
        return size.toBytes() > 0;
    }

    private static String key(String suffix) {
        return BuildProperties.PREFIX + "." + suffix;
    }
}
