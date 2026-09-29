package io.pallet.gitintegration.repolink;

import io.pallet.gitintegration.config.CrossTenant;
import java.util.List;
import java.util.UUID;

/** A deliberately broken tenant repository that ArchitectureTest's tenancy rule must reject. */
public interface TenantRuleFixtureRepository {

    List<UUID> findByOrgIdAndAppId(String orgId, UUID appId);

    List<UUID> findByAppId(UUID appId);

    @CrossTenant("push fan-out: one repository is linked by apps in many orgs")
    List<UUID> findByRepoId(long repoId);

    @CrossTenant(" ")
    List<UUID> findAllBlank();
}
