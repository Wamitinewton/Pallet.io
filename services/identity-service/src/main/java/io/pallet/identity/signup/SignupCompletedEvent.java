package io.pallet.identity.signup;

import java.util.UUID;

/**
 * Raised inside the sign-up transaction and picked up by a {@code @TransactionalEventListener}
 * after commit, so a Kafka publish never happens from inside the mutating transaction.
 */
record SignupCompletedEvent(
        String orgId,
        String orgName,
        String slug,
        UUID userId,
        String ownerUserId,
        String ownerEmail,
        String ownerDisplayName) {}
