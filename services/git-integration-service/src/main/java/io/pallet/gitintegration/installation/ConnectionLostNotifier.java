package io.pallet.gitintegration.installation;

import io.pallet.common.events.NotificationRequested;
import io.pallet.common.outbox.OutboxWriter;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.projection.AppProjection;
import io.pallet.gitintegration.projection.AppProjectionRepository;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells the admins of each org whose connection GitHub broke, in the transaction that recorded the break. Each org's
 * notice names only that org's apps. The dedupe key is built from what caused the break (a delivery, a sync run), so
 * reprocessing the same cause can never send twice.
 */
@Component
public class ConnectionLostNotifier {

    public static final String NOTIFICATION_TYPE = "GIT_CONNECTION_LOST";

    static final String INSTALLATION_SCOPE = "installation";
    static final String ORGANIZATION = "Organization";

    public enum Reason {
        INSTALLATION_DELETED,
        INSTALLATION_SUSPENDED,
        REPOSITORY_ACCESS_REMOVED,
        REPOSITORY_DELETED,
        VERIFIER_ACCESS_LOST
    }

    /** The GitHub account an installation belongs to; {@code type} is {@code User} or {@code Organization}. */
    public record Account(long installationId, String login, String type) {

        public static Account of(Installation installation) {
            return new Account(installation.installationId(), installation.accountLogin(), installation.accountType());
        }

        /** The installation's settings page on GitHub, where the account's admins can configure or uninstall it. */
        String settingsUrl(String webBaseUrl) {
            String path = ORGANIZATION.equals(type)
                    ? "/organizations/" + login + "/settings/installations/"
                    : "/settings/installations/";
            return webBaseUrl + path + installationId;
        }
    }

    private final AppProjectionRepository apps;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final String webBaseUrl;

    ConnectionLostNotifier(
            AppProjectionRepository apps, OutboxWriter outbox, Clock clock, GitIntegrationProperties properties) {
        this.apps = apps;
        this.outbox = outbox;
        this.clock = clock;
        this.webBaseUrl = properties.github().webBaseUrl().toString().replaceAll("/+$", "");
    }

    /** One notice per org about the installation as a whole, naming the org's apps in {@code appsByOrg}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void installationLost(String cause, Account account, Map<String, List<UUID>> appsByOrg, Reason reason) {
        appsByOrg.forEach((orgId, appIds) -> notify(cause, account, orgId, null, "", appIds, reason));
    }

    /** One notice per org and repository the teardown disconnected. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void repositoriesLost(String cause, Account account, TeardownResult result, Reason reason) {
        result.appsByRepositoryAndOrg()
                .forEach((repoId, byOrg) -> byOrg.forEach((orgId, appIds) ->
                        notify(cause, account, orgId, repoId, result.repositoryName(repoId), appIds, reason)));
    }

    private void notify(
            String cause,
            Account account,
            String orgId,
            Long repoId,
            String repository,
            Collection<UUID> appIds,
            Reason reason) {
        String dedupeKey = String.join(
                ":", "git-connection-lost", cause, orgId, repoId == null ? INSTALLATION_SCOPE : repoId.toString());
        Map<String, Object> variables = Map.of(
                "accountLogin", account.login(),
                "repository", repository,
                "appSlugs", slugs(orgId, appIds),
                "reason", reason.name(),
                "settingsUrl", account.settingsUrl(webBaseUrl));
        outbox.append(new NotificationRequested(
                UUID.randomUUID(),
                NotificationRequested.TYPE,
                orgId,
                clock.instant(),
                NOTIFICATION_TYPE,
                null,
                null,
                dedupeKey,
                variables,
                NotificationRequested.AUDIENCE_ORG_ADMINS));
    }

    private List<String> slugs(String orgId, Collection<UUID> appIds) {
        if (appIds.isEmpty()) {
            return List.of();
        }
        return apps.findByOrgIdAndAppIdIn(orgId, appIds).stream()
                .map(AppProjection::slug)
                .sorted()
                .toList();
    }
}
