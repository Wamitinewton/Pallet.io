package io.pallet.common.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-instance guard: fine for local dev and tests, wrong for a scaled-out
 * consumer (each instance keeps its own map). Expiry is lazy — an entry past its
 * retention is treated as absent on the next access and swept opportunistically.
 */
public class InMemoryEventIdempotencyGuard implements EventIdempotencyGuard {

    private final ConcurrentHashMap<String, Instant> reservations = new ConcurrentHashMap<>();

    @Override
    public boolean markProcessed(String eventId, Duration retention) {
        Instant now = Instant.now();
        Instant newExpiry = now.plus(retention);
        boolean[] won = {false};
        // compute runs atomically per key, so exactly one racing caller sees no live reservation.
        reservations.compute(eventId, (key, existing) -> {
            if (existing == null || existing.isBefore(now)) {
                won[0] = true;
                return newExpiry;
            }
            return existing;
        });
        if (won[0]) {
            reservations.values().removeIf(expiry -> expiry.isBefore(now));
        }
        return won[0];
    }

    @Override
    public void release(String eventId) {
        reservations.remove(eventId);
    }
}
