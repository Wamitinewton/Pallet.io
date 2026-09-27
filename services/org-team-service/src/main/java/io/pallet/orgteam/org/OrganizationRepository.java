package io.pallet.orgteam.org;

import io.pallet.orgteam.config.CallerScoped;
import io.pallet.orgteam.config.CrossTenant;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OrganizationRepository extends JpaRepository<Organization, String> {

    /** @return 1 if the organization was created, 0 if {@code orgId} already existed */
    @Modifying
    @Query(value = """
            INSERT INTO org_team.organizations (org_id, name, slug, owner_user_id, kind, status, created_at, updated_at)
            VALUES (:orgId, :name, :slug, :ownerUserId, :kind, 'ACTIVE', :now, :now)
            ON CONFLICT (org_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(String orgId, String name, String slug, String ownerUserId, String kind, Instant now);

    @CallerScoped("lists the organizations the calling account belongs to")
    @Query(value = """
                    select new io.pallet.orgteam.org.OrgSummaryDto(o.orgId, o.name, o.slug, o.kind, m.role)
                    from Organization o
                    join Membership m on m.orgId = o.orgId
                    where m.userId = :userId
                      and m.status = io.pallet.orgteam.member.MembershipStatus.ACTIVE
                      and o.status = io.pallet.orgteam.org.OrgStatus.ACTIVE
                    """, countQuery = """
                    select count(o)
                    from Organization o
                    join Membership m on m.orgId = o.orgId
                    where m.userId = :userId
                      and m.status = io.pallet.orgteam.member.MembershipStatus.ACTIVE
                      and o.status = io.pallet.orgteam.org.OrgStatus.ACTIVE
                    """)
    Page<OrgSummaryDto> findMyOrgs(String userId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Organization o where o.orgId = :orgId")
    Optional<Organization> lockById(String orgId);

    @CrossTenant("purge job selects deleted organizations across tenants")
    @Query(value = """
            SELECT org_id FROM org_team.organizations
            WHERE status = 'DELETED' AND purged_at IS NULL
              AND deleted_at < now() - make_interval(secs => :windowSeconds)
            ORDER BY deleted_at
            LIMIT :limit
            """, nativeQuery = true)
    List<String> findPurgeable(double windowSeconds, int limit);

    @Query(value = """
            SELECT org_id FROM org_team.organizations
            WHERE org_id = :orgId AND status = 'DELETED' AND purged_at IS NULL
              AND deleted_at < now() - make_interval(secs => :windowSeconds)
            FOR UPDATE
            """, nativeQuery = true)
    Optional<String> lockIfPurgeable(String orgId, double windowSeconds);

    @Modifying
    @Query(value = """
            UPDATE org_team.organizations
            SET name = :name, purged_at = now(), updated_at = now(), version = version + 1
            WHERE org_id = :orgId
            """, nativeQuery = true)
    void markPurged(String orgId, String name);
}
