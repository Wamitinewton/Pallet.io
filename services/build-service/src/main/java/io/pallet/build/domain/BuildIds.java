package io.pallet.build.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;

/**
 * Deterministic identities derived from the queue's {@code buildId}: an event about a build is named by the build and
 * what happened, so a retried transaction or a relay retry can never mint a second identity; the build's Kubernetes
 * objects are named by the build, so the API server refuses a second one.
 */
public final class BuildIds {

    public enum EventKind {
        STARTED,
        SUCCEEDED,
        FAILED;

        String discriminator() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Changing this renames every build event this service has published. */
    static final UUID NAMESPACE = UUID.fromString("99cbc45d-2c80-4933-b20e-9e0cf12b949a");

    static final int MAX_OBJECT_NAME_LENGTH = 63;

    private static final String OBJECT_PREFIX = "build-";
    private static final String SOURCE_SECRET_SUFFIX = "-source";

    private BuildIds() {}

    public static UUID eventId(String buildId, EventKind kind) {
        return nameBased(NAMESPACE, buildId + ":" + kind.discriminator());
    }

    public static String jobName(String buildId) {
        return objectName(OBJECT_PREFIX + buildId);
    }

    public static String sourceSecretName(String buildId) {
        return objectName(OBJECT_PREFIX + buildId + SOURCE_SECRET_SUFFIX);
    }

    /** @throws IllegalArgumentException unless {@code name} is a valid Kubernetes DNS label */
    private static String objectName(String name) {
        boolean valid = name.length() <= MAX_OBJECT_NAME_LENGTH
                && name.chars().allMatch(c -> c == '-' || isLowerAlphanumeric(c))
                && isLowerAlphanumeric(name.charAt(name.length() - 1));
        if (!valid) {
            throw new IllegalArgumentException("buildId does not form a valid Kubernetes object name");
        }
        return name;
    }

    private static boolean isLowerAlphanumeric(int c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
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
