package io.pallet.gitintegration.security;

import io.pallet.gitintegration.installation.InstallationLink;
import io.pallet.gitintegration.installation.InstallationLinkRepository;
import io.pallet.gitintegration.projection.AppProjection;
import io.pallet.gitintegration.projection.AppProjectionRepository;
import io.pallet.gitintegration.security.AccessExceptions.AppNotFoundException;
import io.pallet.gitintegration.security.AccessExceptions.InstallationNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Scopes a path's {@code appId} or {@code installationId} to the path's org. Another org's resource and a nonexistent
 * one are the same {@code 404}.
 */
@Component
public class ResourceScope {

    private final AppProjectionRepository apps;
    private final InstallationLinkRepository installationLinks;

    ResourceScope(AppProjectionRepository apps, InstallationLinkRepository installationLinks) {
        this.apps = apps;
        this.installationLinks = installationLinks;
    }

    /** @throws AppNotFoundException unless the app is an active app of this org */
    public void requireApp(String orgId, UUID appId) {
        apps.findByOrgIdAndAppId(orgId, appId)
                .filter(app -> app.status() == AppProjection.Status.ACTIVE)
                .orElseThrow(AppNotFoundException::new);
    }

    /** @throws InstallationNotFoundException unless this org has an active link to the installation */
    public void requireInstallation(String orgId, long installationId) {
        installationLinks
                .findLink(orgId, installationId)
                .filter(link -> link.status() == InstallationLink.Status.ACTIVE)
                .orElseThrow(InstallationNotFoundException::new);
    }
}
