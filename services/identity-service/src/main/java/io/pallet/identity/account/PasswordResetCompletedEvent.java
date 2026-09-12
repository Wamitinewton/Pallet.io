package io.pallet.identity.account;

import java.util.UUID;

/**
 * Raised once a reset token's local {@code used_at} write commits, picked up by a
 * {@code @TransactionalEventListener} after commit so the audit publish never happens from inside
 * the mutating transaction.
 */
record PasswordResetCompletedEvent(String orgId, UUID userId, String keycloakUserId) {}
