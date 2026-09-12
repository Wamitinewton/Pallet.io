package io.pallet.identity.account;

/**
 * Raised once a profile update's local {@code display_name} write commits, picked up by a
 * {@code @TransactionalEventListener} after commit so the platform-event publish never happens
 * from inside the mutating transaction.
 */
record UserProfileUpdatedEvent(String orgId, String keycloakUserId, String email, String displayName) {}
