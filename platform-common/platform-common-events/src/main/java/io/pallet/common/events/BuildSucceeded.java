package io.pallet.common.events;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.time.Instant;
import java.util.UUID;

/**
 * A build of one app at one commit pushed an image. Published by {@code build-service}; see {@link BuildStarted}.
 * {@code imageDigest} is the image's identity, so consumers pull by digest, never by tag.
 */
public record BuildSucceeded(
        UUID eventId,
        String eventType,
        String orgId,
        Instant occurredAt,
        String buildId,
        String appId,
        String commitSha,
        UUID pushEventId,
        String branch,
        String strategy,
        String imageRepository,
        String imageTag,
        String imageDigest,
        @JsonSetter(nulls = Nulls.AS_EMPTY) long durationMs)
        implements PlatformEvent {

    public static final String TYPE = Topics.BUILD_SUCCEEDED;
    public static final String STRATEGY_DOCKERFILE = "DOCKERFILE";
    public static final String STRATEGY_BUILDPACKS = "BUILDPACKS";

    /**
     * Takes {@code eventId} and {@code occurredAt} from the caller because the producer derives the id
     * deterministically from the {@code buildId}.
     */
    public static BuildSucceeded of(UUID eventId, String orgId, Instant occurredAt, Succeeded succeeded) {
        return new BuildSucceeded(
                eventId,
                TYPE,
                orgId,
                occurredAt,
                succeeded.buildId(),
                succeeded.appId(),
                succeeded.commitSha(),
                succeeded.pushEventId(),
                succeeded.branch(),
                succeeded.strategy(),
                succeeded.imageRepository(),
                succeeded.imageTag(),
                succeeded.imageDigest(),
                succeeded.durationMs());
    }

    /**
     * Mints a random id, which a producer that may retry its transaction must not do.
     *
     * @deprecated use {@link #of(UUID, String, Instant, Succeeded)}
     */
    @Deprecated(since = "0.16.0")
    public static BuildSucceeded of(String orgId, String buildId, String appId, String commitSha, UUID pushEventId) {
        return new BuildSucceeded(
                UUID.randomUUID(),
                TYPE,
                orgId,
                Instant.now(),
                buildId,
                appId,
                commitSha,
                pushEventId,
                null,
                null,
                null,
                null,
                null,
                0L);
    }

    public record Succeeded(
            String buildId,
            String appId,
            String commitSha,
            UUID pushEventId,
            String branch,
            String strategy,
            String imageRepository,
            String imageTag,
            String imageDigest,
            long durationMs) {}
}
