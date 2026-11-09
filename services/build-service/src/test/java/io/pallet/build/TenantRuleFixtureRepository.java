package io.pallet.build;

import io.pallet.build.config.CrossTenant;
import java.util.List;

/** A deliberately broken tenant repository that ArchitectureTest's tenancy rule must reject. */
public interface TenantRuleFixtureRepository {

    List<String> findByOrgIdAndAppId(String orgId, String appId);

    List<String> findByAppId(String appId);

    @CrossTenant("admission scans every org's waiting builds")
    List<String> findAdmissionCandidates(int limit);

    @CrossTenant(" ")
    List<String> findAllBlank();
}
