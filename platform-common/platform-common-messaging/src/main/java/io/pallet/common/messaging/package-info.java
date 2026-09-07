/**
 * Shared Kafka wiring: the idempotent {@link io.pallet.common.messaging.PlatformEventPublisher},
 * the {@code kafkaListenerContainerFactory} every service's {@code @KafkaListener} picks up (retry,
 * dead-lettering, correlation, observation), the {@link io.pallet.common.messaging.EventIdempotencyGuard}
 * consumer-dedupe gate, and dead-letter monitoring. Contributed through
 * {@link io.pallet.common.messaging.PalletMessagingAutoConfiguration}; no component scanning.
 */
package io.pallet.common.messaging;
