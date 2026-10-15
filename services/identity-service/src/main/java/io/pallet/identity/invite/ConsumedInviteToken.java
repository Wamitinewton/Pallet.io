package io.pallet.identity.invite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Replay-protection record for a consumed invite token. The token itself is self-verifying
 * (signature, expiry, purpose), but nothing else stops the same valid token being POSTed twice
 * within its expiry window — this row, keyed by the token's own {@code jti}, is that backstop.
 */
@Entity
@Table(name = "consumed_invite_tokens")
public class ConsumedInviteToken {

    @Id
    @Column(name = "jti")
    private String jti;

    @Column(name = "consumed_at", nullable = false)
    private Instant consumedAt;

    @Column(name = "created_user_id")
    private String createdUserId;

    protected ConsumedInviteToken() {}

    /** {@code createdUserId} is the Keycloak user this invite created, or null when an existing account accepted it. */
    public ConsumedInviteToken(String jti, String createdUserId) {
        this.jti = jti;
        this.consumedAt = Instant.now();
        this.createdUserId = createdUserId;
    }

    public String getJti() {
        return jti;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public String getCreatedUserId() {
        return createdUserId;
    }
}
