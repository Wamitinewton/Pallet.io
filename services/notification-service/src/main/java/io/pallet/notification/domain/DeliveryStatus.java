package io.pallet.notification.domain;

public enum DeliveryStatus {
    PENDING,
    THROTTLED,
    SENT,
    FAILED,
    DEAD_LETTERED
}
