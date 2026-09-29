package io.pallet.common.events;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.time.Instant;
import java.util.UUID;

/**
 * An app should build a commit. {@code git-integration-service} publishes one per linked app, keyed
 * by {@code orgId}; {@code build-queue-service} consumes it. {@code trigger} says whether a webhook, a
 * manual request, a new link, or the head reconciler produced it.
 *
 * <p>{@code headCommitMessage} and {@code headCommitAuthor} are untrusted text copied from the source
 * host. No credential ever appears on this event. The primitive fields read as zero or false when
 * absent, so a payload from before they existed still deserializes under Jackson 3's
 * {@code FAIL_ON_NULL_FOR_PRIMITIVES} default.
 */
public record GitPushReceived(
        UUID eventId,
        String eventType,
        String orgId,
        Instant occurredAt,
        String repo,
        String branch,
        String commitSha,
        String appId,
        String provider,
        @JsonSetter(nulls = Nulls.AS_EMPTY) long installationId,
        @JsonSetter(nulls = Nulls.AS_EMPTY) long repoId,
        String rootDirectory,
        String beforeSha,
        @JsonSetter(nulls = Nulls.AS_EMPTY) boolean forced,
        String trigger,
        String headCommitMessage,
        String headCommitAuthor,
        String deliveryId)
        implements PlatformEvent {

    public static final String TYPE = Topics.GIT_PUSH_RECEIVED;
    public static final String PROVIDER_GITHUB = "GITHUB";
    public static final String TRIGGER_WEBHOOK = "WEBHOOK";
    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_LINKED = "LINKED";
    public static final String TRIGGER_RECONCILED = "RECONCILED";

    /**
     * @deprecated carries none of the fields the build pipeline needs; use
     *     {@link #of(UUID, String, Instant, Push)}.
     */
    @Deprecated(forRemoval = true)
    public static GitPushReceived of(String orgId, String repo, String branch, String commitSha) {
        return of(
                UUID.randomUUID(),
                orgId,
                Instant.now(),
                new Push(repo, branch, commitSha, null, null, 0, 0, null, null, false, null, null, null, null));
    }

    /**
     * Takes {@code eventId} and {@code occurredAt} from the caller because the producer derives the id
     * deterministically, so a retried publish of the same push keeps the same identity.
     */
    public static GitPushReceived of(UUID eventId, String orgId, Instant occurredAt, Push push) {
        return new GitPushReceived(
                eventId,
                TYPE,
                orgId,
                occurredAt,
                push.repo(),
                push.branch(),
                push.commitSha(),
                push.appId(),
                push.provider(),
                push.installationId(),
                push.repoId(),
                push.rootDirectory(),
                push.beforeSha(),
                push.forced(),
                push.trigger(),
                push.headCommitMessage(),
                push.headCommitAuthor(),
                push.deliveryId());
    }

    public record Push(
            String repo,
            String branch,
            String commitSha,
            String appId,
            String provider,
            long installationId,
            long repoId,
            String rootDirectory,
            String beforeSha,
            boolean forced,
            String trigger,
            String headCommitMessage,
            String headCommitAuthor,
            String deliveryId) {}
}
