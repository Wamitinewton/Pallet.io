package io.pallet.orgteam.invite;

import io.pallet.orgteam.config.CrossTenant;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface InviteRepository extends JpaRepository<Invite, UUID> {

    Optional<Invite> findByOrgIdAndId(String orgId, UUID id);

    @Query("""
            select i from Invite i
            where i.orgId = :orgId and lower(i.email) = lower(:email) and i.status = :status
            """)
    Optional<Invite> findByOrgIdAndEmailAndStatus(String orgId, String email, InviteStatus status);

    long countByOrgIdAndStatusAndExpiresAtAfter(String orgId, InviteStatus status, Instant now);

    Page<Invite> findByOrgId(String orgId, Pageable pageable);

    Page<Invite> findByOrgIdAndStatus(String orgId, InviteStatus status, Pageable pageable);

    Page<Invite> findByOrgIdAndStatusAndExpiresAtAfter(
            String orgId, InviteStatus status, Instant now, Pageable pageable);

    @Query("""
            select i from Invite i
            where i.orgId = :orgId
              and (i.status = io.pallet.orgteam.invite.InviteStatus.EXPIRED
                   or (i.status = io.pallet.orgteam.invite.InviteStatus.PENDING and i.expiresAt <= :now))
            """)
    Page<Invite> findExpired(String orgId, Instant now, Pageable pageable);

    @Modifying
    @Query(value = """
            UPDATE org_team.invites SET status = 'REVOKED', responded_at = :now
            WHERE org_id = :orgId AND status = 'PENDING'
            """, nativeQuery = true)
    int revokeAllPending(String orgId, Instant now);

    @CrossTenant("expiry sweep spans every organization")
    @Modifying
    @Query(value = """
            UPDATE org_team.invites SET status = 'EXPIRED', responded_at = now()
            WHERE id IN (
                SELECT id FROM org_team.invites
                WHERE status = 'PENDING' AND expires_at < now()
                ORDER BY expires_at
                LIMIT :batchSize)
            """, nativeQuery = true)
    int expirePending(int batchSize);

    @CrossTenant("retention sweep spans every organization")
    @Modifying
    @Query(value = """
            DELETE FROM org_team.invites
            WHERE id IN (
                SELECT id FROM org_team.invites
                WHERE status <> 'PENDING' AND responded_at < now() - make_interval(secs => :windowSeconds)
                ORDER BY responded_at
                LIMIT :batchSize)
            """, nativeQuery = true)
    int deleteTerminalOlderThan(double windowSeconds, int batchSize);

    @Modifying
    @Query("delete from Invite i where i.orgId = :orgId")
    int deleteAllForOrg(String orgId);
}
