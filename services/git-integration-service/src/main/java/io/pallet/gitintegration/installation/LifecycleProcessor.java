package io.pallet.gitintegration.installation;

import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.outbox.OutboxWriter;
import io.pallet.gitintegration.audit.AuditEvents;
import io.pallet.gitintegration.delivery.DeliveryContext;
import io.pallet.gitintegration.delivery.DeliveryHandler;
import io.pallet.gitintegration.delivery.DeliveryOutcome;
import io.pallet.gitintegration.delivery.SubscribedEvents;
import io.pallet.gitintegration.delivery.payload.InstallationInfo;
import io.pallet.gitintegration.delivery.payload.InstallationPayload;
import io.pallet.gitintegration.delivery.payload.InstallationRepositoriesPayload;
import io.pallet.gitintegration.delivery.payload.RepositoryPayload;
import io.pallet.gitintegration.delivery.payload.RepositoryRef;
import io.pallet.gitintegration.installation.ConnectionLostNotifier.Account;
import io.pallet.gitintegration.installation.ConnectionLostNotifier.Reason;
import io.pallet.gitintegration.repolink.RepoLink;
import io.pallet.gitintegration.repolink.RepoLink.DisconnectReason;
import io.pallet.gitintegration.repolink.RepoLinkRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps installations and their repositories true to GitHub.
 * Every action is idempotent and tolerates GitHub's ordering: a repeated {@code deleted} changes nothing, and nothing
 * moves an installation out of {@code DELETED}. Disconnects go through {@link ConnectionTeardown}, and the orgs GitHub
 * cut off are told in the same transaction.
 */
@Component(LifecycleProcessor.BEAN_NAME)
class LifecycleProcessor implements DeliveryHandler {

    /** Spring's context already registers a {@code lifecycleProcessor}. */
    static final String BEAN_NAME = "installationLifecycleProcessor";

    static final String UNHANDLED_ACTION = "UNHANDLED_ACTION";
    static final String UNKNOWN_INSTALLATION = "UNKNOWN_INSTALLATION";

    private final InstallationRepository installations;
    private final InstallationLinkRepository links;
    private final InstallationRepositoryEntryRepository entries;
    private final RepoLinkRepository repoLinks;
    private final ConnectionTeardown teardown;
    private final ConnectionLostNotifier notifier;
    private final RepositorySync repositorySync;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final Clock clock;

    LifecycleProcessor(
            InstallationRepository installations,
            InstallationLinkRepository links,
            InstallationRepositoryEntryRepository entries,
            RepoLinkRepository repoLinks,
            ConnectionTeardown teardown,
            ConnectionLostNotifier notifier,
            RepositorySync repositorySync,
            OutboxWriter outbox,
            JsonMapper json,
            Clock clock) {
        this.installations = installations;
        this.links = links;
        this.entries = entries;
        this.repoLinks = repoLinks;
        this.teardown = teardown;
        this.notifier = notifier;
        this.repositorySync = repositorySync;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Set<String> events() {
        return Set.of(
                SubscribedEvents.INSTALLATION, SubscribedEvents.INSTALLATION_REPOSITORIES, SubscribedEvents.REPOSITORY);
    }

    @Override
    public DeliveryOutcome handle(DeliveryContext context) {
        return switch (context.payload()) {
            case InstallationPayload payload -> installation(context.deliveryId(), payload);
            case InstallationRepositoriesPayload payload -> installationRepositories(context.deliveryId(), payload);
            case RepositoryPayload payload -> repository(context.deliveryId(), payload);
            default -> throw new IllegalArgumentException("No lifecycle handling for " + context.event());
        };
    }

    private DeliveryOutcome installation(UUID deliveryId, InstallationPayload payload) {
        InstallationInfo installation = payload.installation();
        return switch (payload.action()) {
            case "created" -> created(installation);
            case "deleted" -> deleted(deliveryId, installation);
            case "suspend" -> suspended(deliveryId, installation);
            case "unsuspend" -> unsuspended(installation);
            case "new_permissions_accepted" -> {
                installations.updatePermissions(installation.id(), permissions(installation));
                yield DeliveryOutcome.PROCESSED;
            }
            default -> DeliveryOutcome.ignored(UNHANDLED_ACTION);
        };
    }

    /** No org can be inferred from a webhook, so it links none; the redirect that races it upserts the same row. */
    private DeliveryOutcome created(InstallationInfo installation) {
        installations.upsertCreated(
                installation.id(),
                installation.account().id(),
                installation.account().login(),
                installation.account().type(),
                installation.repositorySelection(),
                permissions(installation));
        repositorySync.scheduleAfterCommit(installation.id());
        return DeliveryOutcome.PROCESSED;
    }

    private DeliveryOutcome deleted(UUID deliveryId, InstallationInfo installation) {
        if (installations.findById(installation.id()).isEmpty()) {
            return DeliveryOutcome.ignored(UNKNOWN_INSTALLATION);
        }
        TeardownResult result = teardown.installationDeleted(installation.id());
        notifier.installationLost(
                deliveryId.toString(), account(installation), result.appsByOrg(), Reason.INSTALLATION_DELETED);
        return DeliveryOutcome.PROCESSED;
    }

    /** Links stay; push processing ignores the installation until it is back. */
    private DeliveryOutcome suspended(UUID deliveryId, InstallationInfo installation) {
        recordIfUnknown(installation);
        installations.lockStatus(installation.id());
        if (installations.markSuspended(installation.id()) == 0) {
            return DeliveryOutcome.PROCESSED;
        }
        Map<String, List<UUID>> appsByOrg = activeAppsByOrg(installation.id());
        audit(appsByOrg.keySet(), installation.id(), AuditEvents::installationSuspended);
        notifier.installationLost(
                deliveryId.toString(), account(installation), appsByOrg, Reason.INSTALLATION_SUSPENDED);
        return DeliveryOutcome.PROCESSED;
    }

    private DeliveryOutcome unsuspended(InstallationInfo installation) {
        recordIfUnknown(installation);
        installations.lockStatus(installation.id());
        if (installations.markUnsuspended(installation.id()) == 1) {
            audit(links.findActiveOrgIds(installation.id()), installation.id(), AuditEvents::installationUnsuspended);
        }
        return DeliveryOutcome.PROCESSED;
    }

    private DeliveryOutcome installationRepositories(UUID deliveryId, InstallationRepositoriesPayload payload) {
        long installationId = payload.installation().id();
        Optional<String> status = installations.lockStatus(installationId);
        if (status.isEmpty() || Installation.Status.DELETED.name().equals(status.get())) {
            return DeliveryOutcome.ignored(UNKNOWN_INSTALLATION);
        }
        return switch (payload.action()) {
            case "added" -> {
                for (RepositoryRef repository : payload.added()) {
                    entries.upsertAdded(installationId, repository.id(), repository.fullName(), repository.isPrivate());
                }
                repositorySync.scheduleAfterCommit(installationId);
                yield DeliveryOutcome.PROCESSED;
            }
            case "removed" -> {
                removed(
                        deliveryId,
                        account(payload.installation()),
                        ids(payload.removed()),
                        DisconnectReason.REPOSITORY_ACCESS_REMOVED,
                        Reason.REPOSITORY_ACCESS_REMOVED);
                yield DeliveryOutcome.PROCESSED;
            }
            default -> DeliveryOutcome.ignored(UNHANDLED_ACTION);
        };
    }

    private DeliveryOutcome repository(UUID deliveryId, RepositoryPayload payload) {
        RepositoryPayload.Repository repository = payload.repository();
        long installationId = payload.installationId();
        return switch (payload.action()) {
            case "renamed", "transferred" -> {
                entries.rename(repository.id(), repository.fullName());
                repoLinks.renameRepository(repository.id(), repository.fullName());
                yield DeliveryOutcome.PROCESSED;
            }
            case "deleted" -> {
                Optional<Installation> installation = installations.findById(installationId);
                if (installation.isEmpty()) {
                    yield DeliveryOutcome.ignored(UNKNOWN_INSTALLATION);
                }
                removed(
                        deliveryId,
                        Account.of(installation.get()),
                        Set.of(repository.id()),
                        DisconnectReason.REPOSITORY_DELETED,
                        Reason.REPOSITORY_DELETED);
                yield DeliveryOutcome.PROCESSED;
            }
            case "archived", "unarchived" -> {
                entries.setArchived(installationId, repository.id(), "archived".equals(payload.action()));
                yield DeliveryOutcome.PROCESSED;
            }
            default -> DeliveryOutcome.ignored(UNHANDLED_ACTION);
        };
    }

    /** The teardown takes the installation lock before the read model changes, the order every writer here keeps. */
    private void removed(
            UUID deliveryId, Account account, Set<Long> repoIds, DisconnectReason disconnect, Reason reason) {
        if (repoIds.isEmpty()) {
            return;
        }
        TeardownResult result = teardown.repositoriesRemoved(account.installationId(), repoIds, disconnect);
        entries.deleteRepositories(account.installationId(), repoIds);
        notifier.repositoriesLost(deliveryId.toString(), account, result, reason);
    }

    private void recordIfUnknown(InstallationInfo installation) {
        installations.insertIfAbsent(
                installation.id(),
                installation.account().id(),
                installation.account().login(),
                installation.account().type(),
                installation.repositorySelection(),
                permissions(installation));
    }

    private Map<String, List<UUID>> activeAppsByOrg(long installationId) {
        Map<String, List<UUID>> byOrg = new TreeMap<>();
        links.findActiveOrgIds(installationId).forEach(orgId -> byOrg.put(orgId, new ArrayList<>()));
        for (RepoLink link : repoLinks.findActiveOnInstallation(installationId)) {
            byOrg.computeIfAbsent(link.orgId(), ignored -> new ArrayList<>()).add(link.appId());
        }
        return byOrg;
    }

    private void audit(Iterable<String> orgIds, long installationId, InstallationAudit event) {
        Instant now = clock.instant();
        orgIds.forEach(orgId -> outbox.append(event.apply(orgId, installationId, now)));
    }

    private String permissions(InstallationInfo installation) {
        return json.writeValueAsString(installation.permissions());
    }

    private static Account account(InstallationInfo installation) {
        return new Account(
                installation.id(),
                installation.account().login(),
                installation.account().type());
    }

    private static Set<Long> ids(List<RepositoryRef> repositories) {
        Set<Long> ids = new LinkedHashSet<>();
        repositories.forEach(repository -> ids.add(repository.id()));
        return ids;
    }

    @FunctionalInterface
    private interface InstallationAudit {

        AuditEventRecorded apply(String orgId, long installationId, Instant at);
    }
}
