package io.pallet.gitintegration.installation;

import io.pallet.gitintegration.repolink.RepoLink.DisconnectReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * What one {@link ConnectionTeardown} call changed, and why: the repo links it disconnected, the orgs whose installation
 * link it ended, and the installations it left with no {@code ACTIVE} link. The caller notifies and reports from this,
 * never from a second read.
 *
 * @param cause null only for {@link #NONE}
 */
public record TeardownResult(
        DisconnectReason cause,
        List<DisconnectedLink> disconnected,
        List<String> unlinkedOrgs,
        List<Long> becameUnused) {

    public static final TeardownResult NONE = new TeardownResult(null, List.of(), List.of(), List.of());

    public record DisconnectedLink(String orgId, UUID appId, long installationId, long repoId, String repoFullName) {}

    public TeardownResult {
        disconnected = List.copyOf(disconnected);
        unlinkedOrgs = List.copyOf(unlinkedOrgs);
        becameUnused = List.copyOf(becameUnused);
    }

    /** {@link #NONE} when nothing changed, so a repeated teardown compares equal to it whatever its cause. */
    static TeardownResult of(
            DisconnectReason cause,
            List<DisconnectedLink> disconnected,
            List<String> unlinkedOrgs,
            List<Long> becameUnused) {
        TeardownResult result = new TeardownResult(cause, disconnected, unlinkedOrgs, becameUnused);
        return result.isEmpty() ? NONE : result;
    }

    public boolean isEmpty() {
        return disconnected.isEmpty() && unlinkedOrgs.isEmpty() && becameUnused.isEmpty();
    }

    /** Every affected org and the apps it lost, in org order; an org that lost only its installation link has none. */
    public Map<String, List<UUID>> appsByOrg() {
        Map<String, List<UUID>> byOrg = new TreeMap<>();
        unlinkedOrgs.forEach(orgId -> byOrg.computeIfAbsent(orgId, ignored -> new ArrayList<>()));
        disconnected.forEach(link -> byOrg.computeIfAbsent(link.orgId(), ignored -> new ArrayList<>())
                .add(link.appId()));
        return byOrg;
    }

    /** The disconnected links grouped by repository, then by org, both in id order. */
    public Map<Long, Map<String, List<UUID>>> appsByRepositoryAndOrg() {
        Map<Long, Map<String, List<UUID>>> byRepository = new TreeMap<>();
        disconnected.forEach(link -> byRepository
                .computeIfAbsent(link.repoId(), ignored -> new TreeMap<>())
                .computeIfAbsent(link.orgId(), ignored -> new ArrayList<>())
                .add(link.appId()));
        return byRepository;
    }

    /** The name each disconnected link last recorded for its repository. */
    public String repositoryName(long repoId) {
        return disconnected.stream()
                .filter(link -> link.repoId() == repoId)
                .map(DisconnectedLink::repoFullName)
                .findFirst()
                .orElse("");
    }
}
