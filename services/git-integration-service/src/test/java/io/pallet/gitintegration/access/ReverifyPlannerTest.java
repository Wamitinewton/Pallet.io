package io.pallet.gitintegration.access;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.UnitTest;
import io.pallet.gitintegration.access.ReverifyPlanner.Batch;
import io.pallet.gitintegration.access.ReverifyPlanner.Candidate;
import io.pallet.gitintegration.access.ReverifyPlanner.Plan;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;
import org.junit.jupiter.api.Test;

@UnitTest
class ReverifyPlannerTest {

    private static final LongPredicate ANY_BUDGET = installationId -> true;

    @Test
    void theLeastRecentlyCheckedInstallationIsCheckedFirst() {
        List<Candidate> oldestFirst = List.of(link("org-a", 2), link("org-b", 1), link("org-c", 2));

        Plan plan = ReverifyPlanner.plan(oldestFirst.iterator(), ANY_BUDGET, 10);

        assertThat(plan.batches()).extracting(Batch::installationId).containsExactly(2L, 1L);
        assertThat(plan.batches().getFirst().links())
                .extracting(Candidate::orgId)
                .containsExactly("org-a", "org-c");
    }

    @Test
    void anOrgsTurnsStayInterleavedWithAnotherOrgsOnTheSameInstallation() {
        List<Candidate> turns = new ArrayList<>();
        for (int turn = 0; turn < 3; turn++) {
            turns.add(link("org-big", 1));
            if (turn == 0) {
                turns.add(link("org-small", 1));
            }
        }

        Plan plan = ReverifyPlanner.plan(turns.iterator(), ANY_BUDGET, 2);

        assertThat(plan.batches())
                .singleElement()
                .satisfies(batch ->
                        assertThat(batch.links()).extracting(Candidate::orgId).containsExactly("org-big", "org-small"));
    }

    @Test
    void planningStopsAtTheCapWithoutReadingFurther() {
        AtomicInteger read = new AtomicInteger();
        Iterator<Candidate> endless = new Iterator<>() {
            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public Candidate next() {
                read.incrementAndGet();
                return link("org-a", 1);
            }
        };

        Plan plan = ReverifyPlanner.plan(endless, ANY_BUDGET, 5);

        assertThat(plan.links()).isEqualTo(5);
        assertThat(read).hasValue(5);
    }

    @Test
    void anInstallationUnderItsReserveIsSkippedWithoutSpendingTheCap() {
        List<Candidate> rows = List.of(link("org-a", 1), link("org-b", 9), link("org-a", 1), link("org-c", 2));
        LongPredicate budget = installationId -> installationId != 9;

        Plan plan = ReverifyPlanner.plan(rows.iterator(), budget, 3);

        assertThat(plan.skippedInstallations()).containsExactly(9L);
        assertThat(plan.links()).isEqualTo(3);
        assertThat(plan.batches()).extracting(Batch::installationId).containsExactly(1L, 2L);
    }

    @Test
    void eachInstallationsBudgetIsAskedOnce() {
        AtomicInteger asked = new AtomicInteger();
        List<Candidate> rows = List.of(link("org-a", 1), link("org-b", 1), link("org-c", 1));

        ReverifyPlanner.plan(
                rows.iterator(),
                installationId -> {
                    asked.incrementAndGet();
                    return true;
                },
                10);

        assertThat(asked).hasValue(1);
    }

    @Test
    void nothingDueIsAnEmptyPlan() {
        Plan plan = ReverifyPlanner.plan(List.<Candidate>of().iterator(), ANY_BUDGET, 10);

        assertThat(plan.batches()).isEmpty();
        assertThat(plan.skippedInstallations()).isEmpty();
    }

    private static Candidate link(String orgId, long installationId) {
        return new Candidate(UUID.randomUUID(), orgId, installationId, 100 + installationId, 7, "verifier", 0);
    }
}
