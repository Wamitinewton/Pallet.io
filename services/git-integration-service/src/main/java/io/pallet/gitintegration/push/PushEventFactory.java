package io.pallet.gitintegration.push;

import io.pallet.common.events.GitPushReceived;
import io.pallet.gitintegration.delivery.payload.PushPayload;
import io.pallet.gitintegration.delivery.payload.PushPayload.HeadCommit;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds every {@link GitPushReceived}. The event id is a UUIDv5 over the trigger's own id (the delivery GUID or a
 * nonce) and the app, so a retry that already reached the outbox can't mint a second identity for the same push, and
 * one push fanning out to several apps still gets one id per app. The org is always the link's own.
 */
@Component
public class PushEventFactory {

    static final UUID NAMESPACE = UUID.fromString("7d1f0b62-3c9e-5a4b-9f2e-4a8c1e6d3b57");

    private final Clock clock;

    PushEventFactory(Clock clock) {
        this.clock = clock;
    }

    public GitPushReceived fromWebhook(PushTarget link, PushPayload push, UUID deliveryId) {
        String branch = push.branch().orElseThrow(() -> new IllegalArgumentException("A tag push builds nothing"));
        HeadCommit head = push.headCommit().orElse(null);
        return event(
                deliveryId,
                link,
                new GitPushReceived.Push(
                        push.repositoryFullName(),
                        branch,
                        push.after(),
                        link.appId().toString(),
                        GitPushReceived.PROVIDER_GITHUB,
                        link.installationId(),
                        link.repoId(),
                        link.rootDirectory(),
                        push.before(),
                        push.forced(),
                        GitPushReceived.TRIGGER_WEBHOOK,
                        head == null ? null : head.message(),
                        head == null ? null : head.author(),
                        deliveryId.toString()));
    }

    /** The id is derived from the idempotency key, so a request replayed after a crash names the same event. */
    public GitPushReceived manual(PushTarget link, String branch, String beforeSha, String sha, String idempotencyKey) {
        UUID requestNonce = nameBased(NAMESPACE, "manual:" + idempotencyKey);
        return event(requestNonce, link, push(link, branch, beforeSha, sha, GitPushReceived.TRIGGER_MANUAL));
    }

    public GitPushReceived linked(PushTarget link, String sha) {
        return event(
                UUID.randomUUID(),
                link,
                push(link, link.productionBranch(), null, sha, GitPushReceived.TRIGGER_LINKED));
    }

    public GitPushReceived reconciled(PushTarget link, String before, String after, UUID runNonce) {
        return event(
                runNonce, link, push(link, link.productionBranch(), before, after, GitPushReceived.TRIGGER_RECONCILED));
    }

    private static GitPushReceived.Push push(
            PushTarget link, String branch, String beforeSha, String sha, String trigger) {
        return new GitPushReceived.Push(
                link.repoFullName(),
                branch,
                sha,
                link.appId().toString(),
                GitPushReceived.PROVIDER_GITHUB,
                link.installationId(),
                link.repoId(),
                link.rootDirectory(),
                beforeSha,
                false,
                trigger,
                null,
                null,
                null);
    }

    private GitPushReceived event(UUID triggerId, PushTarget link, GitPushReceived.Push push) {
        return GitPushReceived.of(eventId(triggerId, link.appId()), link.orgId(), clock.instant(), push);
    }

    static UUID eventId(UUID triggerId, UUID appId) {
        return nameBased(NAMESPACE, triggerId + ":" + appId);
    }

    /** RFC 9562 version 5. SHA-1 is what the version defines; it names, it doesn't protect. */
    static UUID nameBased(UUID namespace, String name) {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM provides SHA-1", e);
        }
        sha1.update(ByteBuffer.allocate(16)
                .putLong(namespace.getMostSignificantBits())
                .putLong(namespace.getLeastSignificantBits())
                .array());
        byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        ByteBuffer bits = ByteBuffer.wrap(hash, 0, 16);
        return new UUID(bits.getLong(), bits.getLong());
    }
}
