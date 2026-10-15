package io.pallet.common.events;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.time.Instant;
import java.util.UUID;

/**
 * The queue handed a build to {@code build-service}, its one intended consumer. Published by
 * {@code build-queue-service} for the first dispatch and for every redispatch of the same build.
 *
 * <p>{@code buildId} is the consumer's idempotency key across every attempt, and an attempt must not start
 * after {@code startBy}. No credential, credentialed URL or environment variable ever appears on this event.
 */
public record BuildDispatched(
        UUID eventId,
        String eventType,
        String orgId,
        Instant occurredAt,
        String buildId,
        @JsonSetter(nulls = Nulls.AS_EMPTY) int attempt,
        String appId,
        String branch,
        String commitSha,
        UUID pushEventId,
        String trigger,
        String provider,
        @JsonSetter(nulls = Nulls.AS_EMPTY) long installationId,
        @JsonSetter(nulls = Nulls.AS_EMPTY) long repoId,
        String repo,
        String rootDirectory,
        Instant startBy,
        @JsonSetter(nulls = Nulls.AS_EMPTY) long maxDurationSeconds)
        implements PlatformEvent {

    public static final String TYPE = Topics.BUILD_DISPATCHED;

    /**
     * Takes {@code eventId} and {@code occurredAt} from the caller because the producer derives the id
     * deterministically from the {@code buildId} and the attempt.
     */
    public static BuildDispatched of(UUID eventId, String orgId, Instant occurredAt, Dispatch dispatch) {
        return new BuildDispatched(
                eventId,
                TYPE,
                orgId,
                occurredAt,
                dispatch.buildId(),
                dispatch.attempt(),
                dispatch.appId(),
                dispatch.branch(),
                dispatch.commitSha(),
                dispatch.pushEventId(),
                dispatch.trigger(),
                dispatch.provider(),
                dispatch.installationId(),
                dispatch.repoId(),
                dispatch.repo(),
                dispatch.rootDirectory(),
                dispatch.startBy(),
                dispatch.maxDurationSeconds());
    }

    public record Dispatch(
            String buildId,
            int attempt,
            String appId,
            String branch,
            String commitSha,
            UUID pushEventId,
            String trigger,
            String provider,
            long installationId,
            long repoId,
            String repo,
            String rootDirectory,
            Instant startBy,
            long maxDurationSeconds) {}
}
