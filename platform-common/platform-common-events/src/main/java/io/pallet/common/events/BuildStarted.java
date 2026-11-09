package io.pallet.common.events;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.time.Instant;
import java.util.UUID;

/**
 * A build of one app at one commit started. Published by {@code build-service} once per {@code buildId}.
 * {@code pushEventId} is the {@link GitPushReceived#eventId()} that asked for the build; {@code attempt} is the
 * dispatch attempt that started.
 */
public record BuildStarted(
        UUID eventId,
        String eventType,
        String orgId,
        Instant occurredAt,
        String buildId,
        String appId,
        String commitSha,
        UUID pushEventId,
        @JsonSetter(nulls = Nulls.AS_EMPTY) int attempt,
        String branch)
        implements PlatformEvent {

    public static final String TYPE = Topics.BUILD_STARTED;

    /**
     * Takes {@code eventId} and {@code occurredAt} from the caller because the producer derives the id
     * deterministically from the {@code buildId}.
     */
    public static BuildStarted of(UUID eventId, String orgId, Instant occurredAt, Started started) {
        return new BuildStarted(
                eventId,
                TYPE,
                orgId,
                occurredAt,
                started.buildId(),
                started.appId(),
                started.commitSha(),
                started.pushEventId(),
                started.attempt(),
                started.branch());
    }

    /**
     * Mints a random id, which a producer that may retry its transaction must not do.
     *
     * @deprecated use {@link #of(UUID, String, Instant, Started)}
     */
    @Deprecated(since = "0.16.0")
    public static BuildStarted of(String orgId, String buildId, String appId, String commitSha, UUID pushEventId) {
        return new BuildStarted(
                UUID.randomUUID(), TYPE, orgId, Instant.now(), buildId, appId, commitSha, pushEventId, 0, null);
    }

    public record Started(
            String buildId, String appId, String commitSha, UUID pushEventId, int attempt, String branch) {}
}
