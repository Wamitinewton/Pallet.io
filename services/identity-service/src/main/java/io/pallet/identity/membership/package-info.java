/**
 * Keeps Keycloak in sync with {@code org-team-service}'s membership decisions: one
 * {@code @KafkaListener} per event, each guarded by the platform's default
 * {@link io.pallet.common.messaging.EventIdempotencyGuard} rather than a bespoke one — none of
 * these handlers has a natural unique-constraint insert to key a guard off, unlike
 * {@code notification-service}'s consumer, so there's no concrete reason to build one.
 */
package io.pallet.identity.membership;
