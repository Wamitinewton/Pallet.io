package io.pallet.build.security;

import io.pallet.build.domain.Build;
import io.pallet.build.domain.BuildRepository;
import io.pallet.build.projection.AppProjection;
import io.pallet.build.projection.AppProjectionRepository;
import io.pallet.build.security.AccessExceptions.AppDeletedException;
import io.pallet.build.security.AccessExceptions.AppNotFoundException;
import io.pallet.build.security.AccessExceptions.BuildNotFoundException;
import org.springframework.stereotype.Component;

/**
 * Scopes a path's {@code appId} or {@code buildId} to the path's org. Another org's resource and a nonexistent one are
 * the same {@code 404}.
 */
@Component
public class ResourceScope {

    private final AppProjectionRepository apps;
    private final BuildRepository builds;

    ResourceScope(AppProjectionRepository apps, BuildRepository builds) {
        this.apps = apps;
        this.builds = builds;
    }

    /** A deleted app still counts, so its builds and logs stay readable until retention removes them. */
    public void requireApp(String orgId, String appId) {
        findApp(orgId, appId);
    }

    /** @throws AppDeletedException for an app of this org that has been deleted */
    public void requireActiveApp(String orgId, String appId) {
        if (findApp(orgId, appId).status() == AppProjection.Status.DELETED) {
            throw new AppDeletedException();
        }
    }

    public Build requireBuild(String orgId, String buildId) {
        return builds.findByOrgIdAndBuildId(orgId, buildId).orElseThrow(BuildNotFoundException::new);
    }

    private AppProjection findApp(String orgId, String appId) {
        return apps.findByOrgIdAndAppId(orgId, appId).orElseThrow(AppNotFoundException::new);
    }
}
