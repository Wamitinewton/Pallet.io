package io.pallet.buildqueue.api.dto;

/** The first rule holding a waiting build back, in the order the dispatcher applies them. */
public enum WaitingOn {
    DISPATCH_PAUSED,
    APP_BUSY,
    ORG_CONCURRENCY,
    PLATFORM_CAPACITY,
    NEXT_ROUND
}
