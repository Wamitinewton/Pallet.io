package io.pallet.buildqueue.security;

import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.projection.AppProjectionRepository;
import io.pallet.buildqueue.security.AccessExceptions.AppNotFoundException;
import io.pallet.buildqueue.security.AccessExceptions.BuildNotFoundException;
import java.util.UUID;
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

    /** A deleted app still counts, so its history stays readable. */
    public void requireApp(String orgId, String appId) {
        apps.findByOrgIdAndAppId(orgId, appId).orElseThrow(AppNotFoundException::new);
    }

    public Build requireBuild(String orgId, UUID buildId) {
        return builds.findByOrgIdAndBuildId(orgId, buildId).orElseThrow(BuildNotFoundException::new);
    }
}
