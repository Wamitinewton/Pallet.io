package io.pallet.common.events;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.time.Instant;
import java.util.UUID;

/**
 * A build of one app at one commit failed. Published by {@code build-service}; see {@link BuildStarted}.
 * {@code reason} is written by the producer for people and never contains build output; {@code failureCode} is
 * for machines. {@code failedStep} is null when no step failed, as for a launch or a teardown.
 */
public record BuildFailed(
        UUID eventId,
        String eventType,
        String orgId,
        Instant occurredAt,
        String buildId,
        String appId,
        String commitSha,
        UUID pushEventId,
        String reason,
        String branch,
        String failureCode,
        String failedStep,
        @JsonSetter(nulls = Nulls.AS_EMPTY) long durationMs)
        implements PlatformEvent {

    public static final String TYPE = Topics.BUILD_FAILED;

    public static final String CODE_SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE";
    public static final String CODE_SOURCE_NOT_FOUND = "SOURCE_NOT_FOUND";
    public static final String CODE_DETECTION_FAILED = "DETECTION_FAILED";
    public static final String CODE_BUILD_FAILED = "BUILD_FAILED";
    public static final String CODE_RESOURCE_LIMIT = "RESOURCE_LIMIT";
    public static final String CODE_PUSH_FAILED = "PUSH_FAILED";
    public static final String CODE_TIMEOUT = "TIMEOUT";
    public static final String CODE_LAUNCH_FAILED = "LAUNCH_FAILED";
    public static final String CODE_INFRASTRUCTURE = "INFRASTRUCTURE";
    public static final String CODE_APP_DELETED = "APP_DELETED";
    public static final String CODE_ORG_DELETED = "ORG_DELETED";

    public static final String STEP_FETCH = "FETCH";
    public static final String STEP_BUILD = "BUILD";
    public static final String STEP_PUSH = "PUSH";

    /**
     * Takes {@code eventId} and {@code occurredAt} from the caller because the producer derives the id
     * deterministically from the {@code buildId}.
     */
    public static BuildFailed of(UUID eventId, String orgId, Instant occurredAt, Failed failed) {
        return new BuildFailed(
                eventId,
                TYPE,
                orgId,
                occurredAt,
                failed.buildId(),
                failed.appId(),
                failed.commitSha(),
                failed.pushEventId(),
                failed.reason(),
                failed.branch(),
                failed.failureCode(),
                failed.failedStep(),
                failed.durationMs());
    }

    /**
     * Mints a random id, which a producer that may retry its transaction must not do.
     *
     * @deprecated use {@link #of(UUID, String, Instant, Failed)}
     */
    @Deprecated(since = "0.16.0")
    public static BuildFailed of(
            String orgId, String buildId, String appId, String commitSha, UUID pushEventId, String reason) {
        return new BuildFailed(
                UUID.randomUUID(),
                TYPE,
                orgId,
                Instant.now(),
                buildId,
                appId,
                commitSha,
                pushEventId,
                reason,
                null,
                null,
                null,
                0L);
    }

    public record Failed(
            String buildId,
            String appId,
            String commitSha,
            UUID pushEventId,
            String reason,
            String branch,
            String failureCode,
            String failedStep,
            long durationMs) {}
}
