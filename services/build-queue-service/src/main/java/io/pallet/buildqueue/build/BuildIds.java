package io.pallet.buildqueue.build;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Deterministic identities. A build is named by the push that requested it, and every event about a build by the
 * build and a discriminator, so a redelivery or a retried transaction can never mint a second identity.
 */
public final class BuildIds {

    /** Changing this renames every build and every build event. */
    static final UUID NAMESPACE = UUID.fromString("1bcfc093-c7a6-4e19-ac00-cb2d9a345c49");

    private BuildIds() {}

    public static UUID buildId(UUID pushEventId) {
        return nameBased(NAMESPACE, pushEventId.toString());
    }

    public static UUID queuedEventId(UUID buildId) {
        return nameBased(buildId, "queued");
    }

    public static UUID dispatchedEventId(UUID buildId, int attempt) {
        return nameBased(buildId, "dispatched:" + attempt);
    }

    public static UUID cancelledEventId(UUID buildId) {
        return nameBased(buildId, "cancelled");
    }

    /** A build is cancelled by a person at most once, so its audit record needs no other discriminator. */
    public static UUID cancellationAuditEventId(UUID buildId) {
        return nameBased(buildId, "audit:cancelled");
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
