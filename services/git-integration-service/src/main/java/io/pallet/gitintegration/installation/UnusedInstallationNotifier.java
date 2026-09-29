package io.pallet.gitintegration.installation;

import io.pallet.common.events.NotificationRequested;
import io.pallet.common.outbox.OutboxWriter;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.installation.ConnectionLostNotifier.Account;
import io.pallet.gitintegration.repolink.RepoLink.DisconnectReason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells the admins of the org whose unlink left an installation unused that Pallet will uninstall it, and when, in the
 * transaction that started the clock. Only an unlink reminds anyone: a deleted org's members are already gone from the
 * audience. The dedupe key names the clock it announces, so a later clock on the same installation reminds again.
 */
@Component
class UnusedInstallationNotifier {

    static final String NOTIFICATION_TYPE = "GIT_INSTALLATION_UNUSED";

    private final InstallationRepository installations;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final Duration grace;
    private final String webBaseUrl;

    UnusedInstallationNotifier(
            InstallationRepository installations,
            OutboxWriter outbox,
            Clock clock,
            GitIntegrationProperties properties) {
        this.installations = installations;
        this.outbox = outbox;
        this.clock = clock;
        this.grace = properties.unusedInstallation().grace();
        this.webBaseUrl = properties.github().webBaseUrl().toString().replaceAll("/+$", "");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void remind(TeardownResult result) {
        if (result.cause() != DisconnectReason.INSTALLATION_UNLINKED) {
            return;
        }
        for (long installationId : result.becameUnused()) {
            Installation installation = installations
                    .findById(installationId)
                    .orElseThrow(() -> new IllegalStateException("A locked installation disappeared"));
            Instant unusedSince = installation.unusedSince();
            LocalDate uninstallAfter = LocalDate.ofInstant(unusedSince.plus(grace), ZoneOffset.UTC);
            Map<String, Object> variables = Map.of(
                    "accountLogin", installation.accountLogin(),
                    "settingsUrl", Account.of(installation).settingsUrl(webBaseUrl),
                    "uninstallAfter", uninstallAfter.toString());
            String dedupeKey = "git-installation-unused:" + installationId + ":" + unusedSince;
            result.unlinkedOrgs()
                    .forEach(orgId -> outbox.append(new NotificationRequested(
                            UUID.randomUUID(),
                            NotificationRequested.TYPE,
                            orgId,
                            clock.instant(),
                            NOTIFICATION_TYPE,
                            null,
                            null,
                            dedupeKey,
                            variables,
                            NotificationRequested.AUDIENCE_ORG_ADMINS)));
        }
    }
}
