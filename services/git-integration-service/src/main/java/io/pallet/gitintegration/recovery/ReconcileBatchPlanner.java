package io.pallet.gitintegration.recovery;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongPredicate;

/**
 * Chooses what one head reconciler run fetches. Installations
 * are taken in the order given, which starts after the cursor. Within one, links are taken round-robin across its orgs,
 * one per org per turn, so an org with hundreds of apps can't push another org's few to the back; links sharing a
 * repository branch collapse into one {@link Pair}, fetched once for all of them. An installation whose budget is under
 * the reserve is skipped, and planning stops at {@code maxPairs}.
 */
final class ReconcileBatchPlanner {

    private ReconcileBatchPlanner() {}

    /** A link the reconciler may check, as read outside any lock. */
    record Candidate(UUID appId, String orgId, long installationId, long repoId, String branch) {}

    /** One repository branch, and every link to it the run acts on. */
    record Pair(long installationId, long repoId, String branch, List<Candidate> links) {

        Pair {
            links = List.copyOf(links);
        }
    }

    /**
     * Where the next run starts. {@code resumeAt} is 0 once {@code installationId} was fully planned, so the next run
     * starts after it; otherwise it is how many of that installation's pairs are done, and the next run resumes there.
     */
    record Cursor(long installationId, int resumeAt) {

        static final Cursor START = new Cursor(0, 0);

        static Cursor parse(String value) {
            if (value == null) {
                return START;
            }
            String[] parts = value.split(":", -1);
            if (parts.length != 2) {
                return START;
            }
            try {
                Cursor cursor = new Cursor(Long.parseLong(parts[0]), Integer.parseInt(parts[1]));
                return cursor.installationId() >= 0 && cursor.resumeAt() >= 0 ? cursor : START;
            } catch (NumberFormatException e) {
                return START;
            }
        }

        String format() {
            return installationId + ":" + resumeAt;
        }

        boolean resuming() {
            return resumeAt > 0;
        }
    }

    /**
     * One installation's share of the run.
     *
     * @param firstPair the index of {@code pairs.getFirst()} among the installation's pairs
     * @param complete whether the pairs run to the installation's last
     * @param skipped whether the installation's budget was under the reserve, so nothing is fetched
     */
    record Turn(long installationId, int firstPair, List<Pair> pairs, boolean complete, boolean skipped) {

        Turn {
            pairs = List.copyOf(pairs);
        }

        /** The cursor once {@code done} of this turn's pairs are done. */
        Cursor cursorAt(int done) {
            return complete && done == pairs.size()
                    ? new Cursor(installationId, 0)
                    : new Cursor(installationId, firstPair + done);
        }

        Cursor cursorAfter() {
            return new Cursor(installationId, 0);
        }

        Cursor cursorBefore(int done, Cursor before) {
            return firstPair + done == 0 ? before : new Cursor(installationId, firstPair + done);
        }
    }

    record Plan(List<Turn> turns) {

        Plan {
            turns = List.copyOf(turns);
        }

        int pairs() {
            return turns.stream().mapToInt(turn -> turn.pairs().size()).sum();
        }
    }

    /**
     * @param candidates grouped by installation, starting with the cursor's installation when resuming it, or else the
     *     one after it, and wrapping around; read no further than the plan needs
     */
    static Plan plan(Iterator<Candidate> candidates, Cursor cursor, LongPredicate allowBackground, int maxPairs) {
        List<Turn> turns = new ArrayList<>();
        int planned = 0;
        Candidate next = candidates.hasNext() ? candidates.next() : null;
        boolean first = true;
        while (next != null && planned < maxPairs) {
            long installationId = next.installationId();
            List<Candidate> installation = new ArrayList<>();
            while (next != null && next.installationId() == installationId) {
                installation.add(next);
                next = candidates.hasNext() ? candidates.next() : null;
            }
            int offset =
                    first && cursor.resuming() && cursor.installationId() == installationId ? cursor.resumeAt() : 0;
            first = false;
            if (!allowBackground.test(installationId)) {
                turns.add(new Turn(installationId, 0, List.of(), true, true));
                continue;
            }
            List<Pair> pairs = pairs(roundRobin(installation));
            int from = Math.min(offset, pairs.size());
            int to = Math.min(pairs.size(), from + (maxPairs - planned));
            turns.add(new Turn(installationId, from, pairs.subList(from, to), to == pairs.size(), false));
            planned += to - from;
        }
        return new Plan(turns);
    }

    /** One link per org per turn, orgs and each org's links in the order given. */
    static List<Candidate> roundRobin(List<Candidate> installation) {
        Map<String, Deque<Candidate>> byOrg = new LinkedHashMap<>();
        for (Candidate candidate : installation) {
            byOrg.computeIfAbsent(candidate.orgId(), org -> new ArrayDeque<>()).add(candidate);
        }
        List<Candidate> ordered = new ArrayList<>(installation.size());
        while (ordered.size() < installation.size()) {
            for (Deque<Candidate> links : byOrg.values()) {
                Candidate link = links.poll();
                if (link != null) {
                    ordered.add(link);
                }
            }
        }
        return ordered;
    }

    /** Pairs in the order their first link came up, each with every link to it. */
    private static List<Pair> pairs(List<Candidate> ordered) {
        Map<PairKey, List<Candidate>> byPair = new LinkedHashMap<>();
        for (Candidate link : ordered) {
            byPair.computeIfAbsent(new PairKey(link.repoId(), link.branch()), key -> new ArrayList<>())
                    .add(link);
        }
        List<Pair> pairs = new ArrayList<>(byPair.size());
        byPair.forEach((key, links) ->
                pairs.add(new Pair(links.getFirst().installationId(), key.repoId(), key.branch(), links)));
        return pairs;
    }

    private record PairKey(long repoId, String branch) {}
}
