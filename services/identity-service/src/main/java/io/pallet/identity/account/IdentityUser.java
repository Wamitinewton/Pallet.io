package io.pallet.identity.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Local projection of a Keycloak user. Keycloak owns credentials and session state; this row
 * holds what Keycloak has no place for and what {@code platform-common-security} assumes lives in
 * the token rather than a per-request lookup ({@code org_id}, display name, this service's own
 * notion of account status).
 */
@Entity
@Table(name = "users")
public class IdentityUser {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    @Column(name = "keycloak_user_id", nullable = false)
    private String keycloakUserId;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private UserStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IdentityUser() {}

    public IdentityUser(String orgId, String keycloakUserId, String email, String displayName) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.keycloakUserId = keycloakUserId;
        this.email = email;
        this.displayName = displayName;
        this.status = UserStatus.ACTIVE;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void disable() {
        this.status = UserStatus.DISABLED;
        this.updatedAt = Instant.now();
    }

    public void updateDisplayName(String displayName) {
        this.displayName = displayName;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getOrgId() {
        return orgId;
    }

    public String getKeycloakUserId() {
        return keycloakUserId;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
