package io.pallet.identity.verification;

import java.util.UUID;

/**
 * Raised once a code's local {@code consumed_at} write commits, picked up by a
 * {@code @TransactionalEventListener} after commit so the audit publish never happens from inside
 * the mutating transaction.
 */
record EmailVerifiedEvent(String orgId, UUID userId, String keycloakUserId) {}
