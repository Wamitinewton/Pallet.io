package io.pallet.gitintegration.access;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongPredicate;

/**
 * Chooses what one re-verification run checks. Candidates are taken in the order given, which already shares turns
 * across the orgs on each installation and puts the least recently checked first; an installation whose budget is
 * under the reserve is passed over without counting against the cap, and reading stops at {@code maxLinks}. The
 * chosen links are grouped by installation, in the order each installation first came up, so one installation's
 * checks run one after another on one token.
 */
final class ReverifyPlanner {

    private ReverifyPlanner() {}

    /** A link as read outside any lock; {@code version} is what its outcome is applied against. */
    record Candidate(
            UUID appId,
            String orgId,
            long installationId,
            long repoId,
            long githubUserId,
            String githubLogin,
            long version) {}

    record Batch(long installationId, List<Candidate> links) {

        Batch {
            links = List.copyOf(links);
        }
    }

    record Plan(List<Batch> batches, Set<Long> skippedInstallations) {

        Plan {
            batches = List.copyOf(batches);
            skippedInstallations = Set.copyOf(skippedInstallations);
        }

        int links() {
            return batches.stream().mapToInt(batch -> batch.links().size()).sum();
        }
    }

    static Plan plan(Iterator<Candidate> candidates, LongPredicate allowBackground, int maxLinks) {
        Map<Long, List<Candidate>> byInstallation = new LinkedHashMap<>();
        Map<Long, Boolean> allowed = new LinkedHashMap<>();
        Set<Long> skipped = new LinkedHashSet<>();
        int planned = 0;
        while (planned < maxLinks && candidates.hasNext()) {
            Candidate candidate = candidates.next();
            long installationId = candidate.installationId();
            if (!allowed.computeIfAbsent(installationId, allowBackground::test)) {
                skipped.add(installationId);
                continue;
            }
            byInstallation
                    .computeIfAbsent(installationId, ignored -> new ArrayList<>())
                    .add(candidate);
            planned++;
        }
        List<Batch> batches = new ArrayList<>(byInstallation.size());
        byInstallation.forEach((installationId, links) -> batches.add(new Batch(installationId, links)));
        return new Plan(batches, skipped);
    }
}
