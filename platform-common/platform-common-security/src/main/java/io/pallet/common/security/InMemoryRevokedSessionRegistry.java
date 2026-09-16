package io.pallet.common.security;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-instance registry: fine for local dev and tests, wrong once a service scales past one
 * replica, since a revocation written on one instance is invisible to the others. Expiry is lazy
 * — an entry past its retention is treated as absent on the next access and swept opportunistically.
 */
public class InMemoryRevokedSessionRegistry implements RevokedSessionRegistry {

    private final ConcurrentHashMap<String, Instant> revocations = new ConcurrentHashMap<>();

    @Override
    public void revoke(String sessionId, Duration retention) {
        Instant now = Instant.now();
        revocations.put(sessionId, now.plus(retention));
        revocations.values().removeIf(expiry -> expiry.isBefore(now));
    }

    @Override
    public boolean isRevoked(String sessionId) {
        Instant expiry = revocations.get(sessionId);
        return expiry != null && expiry.isAfter(Instant.now());
    }
}
