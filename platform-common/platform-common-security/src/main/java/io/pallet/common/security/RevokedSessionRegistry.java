package io.pallet.common.security;

import java.time.Duration;

/**
 * Shared record of Keycloak session ids ({@code sid} claim) revoked ahead of their access token's
 * natural expiry — deleting the Keycloak session itself only stops new tokens being issued, it
 * doesn't invalidate ones already handed out. {@link SessionRevocationValidator} checks this on
 * every JWT validation, across every service, so a write here must be visible platform-wide.
 */
public interface RevokedSessionRegistry {

    /**
     * @param retention how long the marker outlives the write — must cover the longest possible
     *     remaining lifetime of a token minted under this session, e.g. the realm's access token
     *     lifespan
     */
    void revoke(String sessionId, Duration retention);

    boolean isRevoked(String sessionId);
}
