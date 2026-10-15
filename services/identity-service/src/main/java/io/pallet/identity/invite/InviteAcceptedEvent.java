package io.pallet.identity.invite;

/**
 * Raised once an invite-accepted account's local insert commits, picked up by a
 * {@code @TransactionalEventListener} after commit so the Kafka publishes never happen from inside
 * the mutating transaction. {@code inviteId} is the accepted token's {@code jti}; {@code userId} is
 * the accepting account's Keycloak user id, new or existing.
 */
record InviteAcceptedEvent(
        String orgId, String inviteId, String userId, String email, String displayName, String role) {}
