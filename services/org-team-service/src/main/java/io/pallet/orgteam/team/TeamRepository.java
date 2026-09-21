package io.pallet.orgteam.team;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TeamRepository extends JpaRepository<Team, UUID> {

    Optional<Team> findByOrgIdAndId(String orgId, UUID id);

    Page<Team> findByOrgId(String orgId, Pageable pageable);

    boolean existsByOrgIdAndId(String orgId, UUID id);

    boolean existsByOrgIdAndSlug(String orgId, String slug);

    long countByOrgId(String orgId);

    @Modifying
    @Query("delete from Team t where t.orgId = :orgId")
    int deleteAllForOrg(String orgId);
}
