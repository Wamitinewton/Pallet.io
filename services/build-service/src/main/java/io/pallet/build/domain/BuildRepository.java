package io.pallet.build.domain;

import io.pallet.build.config.CrossTenant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Reads of {@code builds}. Writes go through {@link BuildTransitions}. */
public interface BuildRepository extends Repository<Build, String> {

    Optional<Build> findByOrgIdAndBuildId(String orgId, String buildId);

    /**
     * The build, row-locked until the caller's transaction ends. Selected by id alone so a dispatch naming a build that
     * belongs to another org is seen, and refused, rather than treated as new.
     */
    @CrossTenant("a dispatch that reuses another tenant's buildId must be detected, never applied")
    @Query(value = "SELECT * FROM build.builds WHERE build_id = :buildId FOR UPDATE", nativeQuery = true)
    Optional<Build> lockByBuildId(@Param("buildId") String buildId);
}
