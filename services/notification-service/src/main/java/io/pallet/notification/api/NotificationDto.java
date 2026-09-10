package io.pallet.notification.api;

import java.time.Instant;
import java.util.UUID;

public record NotificationDto(
        UUID deliveryId, String title, String body, boolean read, Instant sentAt, Instant readAt) {}
