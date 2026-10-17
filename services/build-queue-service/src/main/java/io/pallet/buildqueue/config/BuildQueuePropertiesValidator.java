package io.pallet.buildqueue.config;

import io.pallet.buildqueue.config.BuildQueueProperties.Limits;
import io.pallet.buildqueue.config.BuildQueueProperties.Profile;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/** Refuses to start the service with a configuration whose keys contradict each other. Every failure names its key. */
@Component
public class BuildQueuePropertiesValidator {

    static final String DEFAULT_PROFILE = BuildQueueProperties.PREFIX + ".limits.default-profile";
    static final String PROFILES = BuildQueueProperties.PREFIX + ".limits.profiles";
    static final String ORG_PROFILES = BuildQueueProperties.PREFIX + ".limits.org-profiles";
    static final String START_TIMEOUT = BuildQueueProperties.PREFIX + ".dispatch.start-timeout";
    static final String POLL_INTERVAL = BuildQueueProperties.PREFIX + ".dispatch.poll-interval";
    static final String MAX_ATTEMPTS = BuildQueueProperties.PREFIX + ".dispatch.max-attempts";
    static final String SWEEP_INTERVAL = BuildQueueProperties.PREFIX + ".lease.sweep-interval";
    static final String LEASE_GRACE = BuildQueueProperties.PREFIX + ".lease.grace";
    static final String TOMBSTONES = BuildQueueProperties.PREFIX + ".retention.tombstones";

    /** A tombstone has to outlive {@code git.push.received}'s seven-day retention, or a buffered push revives an app. */
    static final Duration MIN_TOMBSTONE_RETENTION = Duration.ofDays(8);

    BuildQueuePropertiesValidator(BuildQueueProperties properties) {
        validate(properties);
    }

    static void validate(BuildQueueProperties properties) {
        List<String> problems = new ArrayList<>();
        checkLimits(properties.limits(), problems);
        checkDeadlines(properties, problems);
        checkRetention(properties.retention(), problems);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Invalid " + BuildQueueProperties.PREFIX + " configuration: " + String.join("; ", problems));
        }
    }

    private static void checkLimits(Limits limits, List<String> problems) {
        Map<String, Profile> profiles = limits.profiles();
        if (!profiles.containsKey(limits.defaultProfile())) {
            problems.add(DEFAULT_PROFILE + " names '" + limits.defaultProfile() + "', which is not in " + PROFILES);
        }
        new TreeMap<>(limits.orgProfiles()).forEach((orgId, profile) -> {
            if (!profiles.containsKey(profile)) {
                problems.add(ORG_PROFILES + "." + orgId + " names '" + profile + "', which is not in " + PROFILES);
            }
        });
        new TreeMap<>(profiles).forEach((name, profile) -> {
            if (profile.maxConcurrent() < 1) {
                problems.add(PROFILES + "." + name + ".max-concurrent must be at least 1");
            }
            if (profile.maxQueued() < 1) {
                problems.add(PROFILES + "." + name + ".max-queued must be at least 1");
            }
        });
    }

    private static void checkDeadlines(BuildQueueProperties properties, List<String> problems) {
        Duration noticeTime =
                properties.dispatch().pollInterval().plus(properties.lease().sweepInterval());
        if (properties.dispatch().startTimeout().compareTo(noticeTime) <= 0) {
            problems.add(START_TIMEOUT + " must be longer than " + POLL_INTERVAL + " plus " + SWEEP_INTERVAL + " ("
                    + noticeTime + "), or every dispatch is redispatched before it can be noticed");
        }
        if (!properties.lease().grace().isPositive()) {
            problems.add(LEASE_GRACE + " must be positive");
        }
        if (properties.dispatch().maxAttempts() < 1) {
            problems.add(MAX_ATTEMPTS + " must be at least 1");
        }
    }

    private static void checkRetention(BuildQueueProperties.Retention retention, List<String> problems) {
        if (retention.tombstones().compareTo(MIN_TOMBSTONE_RETENTION) < 0) {
            problems.add(TOMBSTONES + " must be at least " + MIN_TOMBSTONE_RETENTION
                    + ": a tombstone has to outlive git.push.received's retention and any dead-letter replay, or a"
                    + " push buffered for a deleted app finds no tombstone and brings the app back");
        }
    }
}
