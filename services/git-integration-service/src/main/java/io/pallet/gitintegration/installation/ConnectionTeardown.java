package io.pallet.gitintegration.installation;

import io.pallet.common.outbox.OutboxWriter;
import io.pallet.gitintegration.audit.Actor;
import io.pallet.gitintegration.audit.AuditEvents;
import io.pallet.gitintegration.github.InstallationTokenCache;
import io.pallet.gitintegration.installation.TeardownResult.DisconnectedLink;
import io.pallet.gitintegration.push.BranchHeadRepository;
import io.pallet.gitintegration.repolink.RepoLink;
import io.pallet.gitintegration.repolink.RepoLink.DisconnectReason;
import io.pallet.gitintegration.repolink.RepoLinkRepository;
import io.pallet.gitintegration.security.AccessExceptions.InstallationNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one place connections end. Every method joins the caller's transaction, takes row locks in one fixed order
 * (installation, then installation links, then repo links by {@code app_id}), so two teardowns touching one
 * installation serialize instead of deadlocking, and returns what it changed for the caller to notify from. Never
 * calls GitHub: nothing here uninstalls anything.
 */
@Component
public class ConnectionTeardown {

    private final InstallationRepository installations;
    private final InstallationLinkRepository links;
    private final RepoLinkRepository repoLinks;
    private final BranchHeadRepository heads;
    private final InstallationTokenCache tokens;
    private final OutboxWriter outbox;
    private final Clock clock;

    ConnectionTeardown(
            InstallationRepository installations,
            InstallationLinkRepository links,
            RepoLinkRepository repoLinks,
            BranchHeadRepository heads,
            InstallationTokenCache tokens,
            OutboxWriter outbox,
            Clock clock) {
        this.installations = installations;
        this.links = links;
        this.repoLinks = repoLinks;
        this.heads = heads;
        this.tokens = tokens;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Ends one org's link and that org's repo links on it; other orgs' links to the installation are untouched.
     *
     * @throws InstallationNotFoundException if this org has no {@code ACTIVE} link to the installation
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TeardownResult unlinkInstallation(String orgId, long installationId, DisconnectReason reason, Actor actor) {
        installations.lockStatus(installationId).orElseThrow(InstallationNotFoundException::new);
        String status = links.lockStatus(orgId, installationId).orElseThrow(InstallationNotFoundException::new);
        if (!InstallationLink.Status.ACTIVE.name().equals(status)) {
            throw new InstallationNotFoundException();
        }
        List<DisconnectedLink> disconnected =
                disconnect(repoLinks.lockActiveOnInstallationOf(orgId, installationId), reason, actor);
        links.unlink(orgId, installationId);
        boolean becameUnused = installations.markUnusedIfNoActiveLink(installationId) == 1;
        outbox.append(AuditEvents.installationUnlinked(
                orgId, actor.id(), installationId, reason.name(), disconnected.size(), clock.instant()));
        return TeardownResult.of(
                reason, disconnected, List.of(orgId), becameUnused ? List.of(installationId) : List.of());
    }

    /** GitHub uninstalled the app: every org's link ends. An unknown or already deleted installation changes nothing. */
    @Transactional(propagation = Propagation.MANDATORY)
    public TeardownResult installationDeleted(long installationId) {
        Optional<String> status = installations.lockStatus(installationId);
        if (status.isEmpty() || Installation.Status.DELETED.name().equals(status.get())) {
            return TeardownResult.NONE;
        }
        DisconnectReason reason = DisconnectReason.INSTALLATION_DELETED;
        List<String> orgIds = links.lockActiveOrgIds(installationId);
        List<DisconnectedLink> disconnected =
                disconnect(repoLinks.lockActiveOnInstallation(installationId), reason, Actor.SYSTEM);
        links.unlinkEveryOrg(installationId);
        Instant now = clock.instant();
        for (String orgId : orgIds) {
            int lost = (int) disconnected.stream()
                    .filter(link -> link.orgId().equals(orgId))
                    .count();
            outbox.append(AuditEvents.installationUnlinked(
                    orgId, Actor.SYSTEM.id(), installationId, reason.name(), lost, now));
        }
        installations.markDeleted(installationId);
        evictTokensAfterCommit(installationId);
        return TeardownResult.of(reason, disconnected, orgIds, List.of());
    }

    /** The installation can no longer reach these repositories: every org's links to them end. */
    @Transactional(propagation = Propagation.MANDATORY)
    public TeardownResult repositoriesRemoved(long installationId, Set<Long> repoIds, DisconnectReason reason) {
        if (repoIds.isEmpty() || installations.lockStatus(installationId).isEmpty()) {
            return TeardownResult.NONE;
        }
        return TeardownResult.of(
                reason,
                disconnect(repoLinks.lockActiveOnRepositories(installationId, repoIds), reason, Actor.SYSTEM),
                List.of(),
                List.of());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public TeardownResult appDeleted(String orgId, UUID appId) {
        return repoLinks
                .lockActive(orgId, appId)
                .map(link -> TeardownResult.of(
                        DisconnectReason.APP_DELETED,
                        disconnect(List.of(link), DisconnectReason.APP_DELETED, Actor.SYSTEM),
                        List.of(),
                        List.of()))
                .orElse(TeardownResult.NONE);
    }

    /**
     * The link's verifier no longer holds enough permission on GitHub. Call with the link's installation row locked
     * first and then the link itself, as every teardown takes them.
     *
     * @param locked the link as read under its row lock, still {@code ACTIVE}
     * @param verifierLogin the verifier's login as GitHub last answered for it
     * @param currentRole the verifier's role on the repository now, {@code none} when the account is gone
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TeardownResult verifierAccessLost(RepoLink locked, String verifierLogin, String currentRole) {
        DisconnectReason reason = DisconnectReason.VERIFIER_ACCESS_LOST;
        List<DisconnectedLink> disconnected = disconnect(List.of(locked), reason, Actor.SYSTEM);
        outbox.append(AuditEvents.repoLinkVerifierAccessLost(
                locked.orgId(),
                locked.appId(),
                verifierLogin,
                locked.verifiedPermission(),
                currentRole,
                clock.instant()));
        return TeardownResult.of(reason, disconnected, List.of(), List.of());
    }

    /**
     * Ends every connection of the org. Other orgs' links to the same installations stay, and each installation it
     * leaves without a link starts its unused clock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TeardownResult orgDeleted(String orgId) {
        List<Long> known = links.findActiveInstallationIds(orgId);
        if (!known.isEmpty()) {
            installations.lockAll(known);
        }
        List<Long> linked = links.lockActiveInstallationIds(orgId);
        if (!linked.isEmpty()) {
            installations.lockAll(linked);
        }
        DisconnectReason reason = DisconnectReason.ORG_DELETED;
        List<DisconnectedLink> disconnected = disconnect(repoLinks.lockAllActive(orgId), reason, Actor.SYSTEM);
        links.unlinkAll(orgId);
        Instant now = clock.instant();
        List<Long> becameUnused = new ArrayList<>();
        for (long installationId : linked) {
            if (installations.markUnusedIfNoActiveLink(installationId) == 1) {
                becameUnused.add(installationId);
            }
            int lost = (int) disconnected.stream()
                    .filter(link -> link.installationId() == installationId)
                    .count();
            outbox.append(AuditEvents.installationUnlinked(
                    orgId, Actor.SYSTEM.id(), installationId, reason.name(), lost, now));
        }
        return TeardownResult.of(reason, disconnected, linked.isEmpty() ? List.of() : List.of(orgId), becameUnused);
    }

    /** Call with every link in {@code locked} row-locked by this transaction. */
    private List<DisconnectedLink> disconnect(List<RepoLink> locked, DisconnectReason reason, Actor actor) {
        if (locked.isEmpty()) {
            return List.of();
        }
        List<UUID> appIds = locked.stream().map(RepoLink::appId).toList();
        repoLinks.disconnectAll(appIds, reason.name());
        heads.deleteAllOf(appIds);
        Instant now = clock.instant();
        for (RepoLink link : locked) {
            outbox.append(AuditEvents.repoLinkDisconnected(link.orgId(), actor.id(), link.appId(), reason.name(), now));
        }
        return locked.stream()
                .map(link -> new DisconnectedLink(
                        link.orgId(), link.appId(), link.installationId(), link.repoId(), link.repoFullName()))
                .toList();
    }

    /** A rollback keeps the installation, so its tokens must survive one. */
    private void evictTokensAfterCommit(long installationId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                tokens.evict(installationId);
            }
        });
    }
}
