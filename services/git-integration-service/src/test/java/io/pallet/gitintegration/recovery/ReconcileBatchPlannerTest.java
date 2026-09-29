package io.pallet.gitintegration.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import io.pallet.common.test.annotations.UnitTest;
import io.pallet.gitintegration.recovery.ReconcileBatchPlanner.Candidate;
import io.pallet.gitintegration.recovery.ReconcileBatchPlanner.Cursor;
import io.pallet.gitintegration.recovery.ReconcileBatchPlanner.Pair;
import io.pallet.gitintegration.recovery.ReconcileBatchPlanner.Plan;
import io.pallet.gitintegration.recovery.ReconcileBatchPlanner.Turn;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.LongPredicate;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

@UnitTest
class ReconcileBatchPlannerTest {

    private static final LongPredicate ANY_BUDGET = installationId -> true;
    private static final String BIG = "org-big";
    private static final String SMALL = "org-small";

    @Test
    void aSmallOrgSharingAnInstallationWithABigOneIsInTheFirstTurns() {
        List<Candidate> rows = new ArrayList<>();
        rows.addAll(links(BIG, 1, 1_000, 400));
        rows.addAll(links(SMALL, 1, 5_000, 3));

        Plan plan = plan(rows, Cursor.START, ANY_BUDGET, 500);

        assertThat(plan.turns().getFirst().pairs().stream()
                        .limit(6)
                        .map(pair -> pair.links().getFirst().orgId()))
                .containsExactly(BIG, SMALL, BIG, SMALL, BIG, SMALL);
        assertThat(plan.pairs()).isEqualTo(403);
    }

    @Test
    void aRepositoryBranchLinkedInSeveralOrgsIsOnePairWithEveryLink() {
        List<Candidate> rows = List.of(
                link("org-a", 1, 7, "main"),
                link("org-b", 1, 7, "main"),
                link("org-c", 1, 7, "main"),
                link("org-c", 1, 7, "release"));

        Plan plan = plan(rows, Cursor.START, ANY_BUDGET, 500);

        assertThat(plan.turns().getFirst().pairs())
                .extracting(Pair::branch, pair -> pair.links().size())
                .containsExactly(tuple("main", 3), tuple("release", 1));
    }

    @Test
    void planningStopsAtTheCapAndTheNextRunResumesWhereItStopped() {
        List<Candidate> rows = new ArrayList<>();
        rows.addAll(links("org-a", 1, 100, 3));
        rows.addAll(links("org-b", 2, 200, 4));
        rows.addAll(links("org-c", 3, 300, 2));

        Plan first = plan(rows, Cursor.START, ANY_BUDGET, 5);

        assertThat(first.pairs()).isEqualTo(5);
        assertThat(first.turns()).extracting(Turn::installationId).containsExactly(1L, 2L);
        Turn partial = first.turns().getLast();
        assertThat(partial.complete()).isFalse();
        Cursor stopped = partial.cursorAt(partial.pairs().size());
        assertThat(stopped).isEqualTo(new Cursor(2, 2));
        assertThat(Cursor.parse(stopped.format())).isEqualTo(stopped);

        Plan second = plan(rotated(rows, stopped), stopped, ANY_BUDGET, 5);

        assertThat(second.turns()).extracting(Turn::installationId).containsExactly(2L, 3L, 1L);
        assertThat(second.turns().getFirst().firstPair()).isEqualTo(2);
        assertThat(second.turns().getFirst().pairs()).extracting(Pair::repoId).containsExactly(202L, 203L);
        assertThat(second.turns().getFirst().cursorAt(2)).isEqualTo(new Cursor(2, 0));
    }

    @Test
    void theRunAfterACompletedInstallationStartsWithTheNextOneAndWrapsAround() {
        List<Candidate> rows = new ArrayList<>();
        rows.addAll(links("org-a", 1, 100, 1));
        rows.addAll(links("org-b", 2, 200, 1));
        rows.addAll(links("org-c", 3, 300, 1));
        Cursor afterFirst = new Cursor(1, 0);

        Plan plan = plan(rotated(rows, afterFirst), afterFirst, ANY_BUDGET, 500);

        assertThat(plan.turns()).extracting(Turn::installationId).containsExactly(2L, 3L, 1L);
        assertThat(plan.turns()).allSatisfy(turn -> assertThat(turn.complete()).isTrue());
    }

    @Test
    void anInstallationUnderItsReserveIsSkippedWithoutSpendingTheCap() {
        List<Candidate> rows = new ArrayList<>();
        rows.addAll(links("org-a", 1, 100, 3));
        rows.addAll(links("org-b", 2, 200, 3));

        Plan plan = plan(rows, Cursor.START, installationId -> installationId != 1, 3);

        assertThat(plan.turns())
                .extracting(Turn::installationId, Turn::skipped)
                .containsExactly(tuple(1L, true), tuple(2L, false));
        assertThat(plan.turns().getFirst().pairs()).isEmpty();
        assertThat(plan.turns().getFirst().cursorAfter()).isEqualTo(new Cursor(1, 0));
        assertThat(plan.turns().getLast().pairs()).hasSize(3);
        assertThat(plan.turns().getLast().complete()).isTrue();
    }

    @Test
    void aRunEndingBeforeAnInstallationsFirstPairKeepsTheCursorItBeganFrom() {
        List<Candidate> rows = new ArrayList<>();
        rows.addAll(links("org-a", 1, 100, 1));
        rows.addAll(links("org-b", 2, 200, 3));
        Plan plan = plan(rows, Cursor.START, ANY_BUDGET, 500);
        Turn fresh = plan.turns().getLast();
        Cursor afterFirst = plan.turns().getFirst().cursorAt(1);

        assertThat(fresh.cursorBefore(0, afterFirst)).isEqualTo(afterFirst);
        assertThat(fresh.cursorBefore(1, afterFirst)).isEqualTo(new Cursor(2, 1));

        Cursor resuming = new Cursor(2, 2);
        Turn resumed =
                plan(rotated(rows, resuming), resuming, ANY_BUDGET, 500).turns().getFirst();

        assertThat(resumed.cursorBefore(0, resuming)).isEqualTo(resuming);
    }

    @Test
    void anUnreadableCursorStartsFromTheBeginning() {
        assertThat(Cursor.parse(null)).isEqualTo(Cursor.START);
        assertThat(Cursor.parse("garbage")).isEqualTo(Cursor.START);
        assertThat(Cursor.parse("12:-1")).isEqualTo(Cursor.START);
        assertThat(Cursor.parse("12:x")).isEqualTo(Cursor.START);
    }

    private static Plan plan(List<Candidate> rows, Cursor cursor, LongPredicate budget, int maxPairs) {
        return ReconcileBatchPlanner.plan(rows.iterator(), cursor, budget, maxPairs);
    }

    /** The order {@code RepoLinkRepository.streamReconcileCandidates} returns for {@code cursor}. */
    private static List<Candidate> rotated(List<Candidate> rows, Cursor cursor) {
        Comparator<Candidate> afterCursorFirst =
                Comparator.comparingInt(row -> row.installationId() > cursor.installationId()
                                || (cursor.resuming() && row.installationId() == cursor.installationId())
                        ? 0
                        : 1);
        return rows.stream()
                .sorted(afterCursorFirst
                        .thenComparingLong(Candidate::installationId)
                        .thenComparing(Candidate::orgId)
                        .thenComparing(Candidate::appId))
                .toList();
    }

    /** {@code count} links of one org, each to its own repository from {@code firstRepoId} on, in app id order. */
    private static List<Candidate> links(String orgId, long installationId, long firstRepoId, int count) {
        return IntStream.range(0, count)
                .mapToObj(
                        i -> new Candidate(new UUID(installationId, i), orgId, installationId, firstRepoId + i, "main"))
                .toList();
    }

    private static Candidate link(String orgId, long installationId, long repoId, String branch) {
        return new Candidate(UUID.randomUUID(), orgId, installationId, repoId, branch);
    }
}
