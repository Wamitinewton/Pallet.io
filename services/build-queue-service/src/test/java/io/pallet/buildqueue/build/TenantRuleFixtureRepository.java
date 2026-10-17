package io.pallet.buildqueue.build;

import io.pallet.buildqueue.config.CrossTenant;
import java.util.List;
import java.util.UUID;

/** A deliberately broken tenant repository that ArchitectureTest's tenancy rule must reject. */
public interface TenantRuleFixtureRepository {

    List<UUID> findByOrgIdAndAppId(String orgId, String appId);

    List<UUID> findByAppId(String appId);

    @CrossTenant("dispatcher candidate scan: every org with waiting work")
    List<UUID> findDispatchCandidates(int limit);

    @CrossTenant(" ")
    List<UUID> findAllBlank();
}
