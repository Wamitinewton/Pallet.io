package io.pallet.buildqueue.build;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.UnitTest;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

@UnitTest
class BuildStatusTest {

    @Test
    void statusesRankInLifecycleOrder() {
        assertThat(BuildStatus.QUEUED.rank()).isZero();
        assertThat(BuildStatus.DISPATCHED.rank()).isEqualTo(1);
        assertThat(BuildStatus.RUNNING.rank()).isEqualTo(2);
        assertThat(BuildStatus.EXPIRED.rank()).isEqualTo(3);
        assertThat(BuildStatus.SUCCEEDED.rank()).isEqualTo(4);
        assertThat(BuildStatus.FAILED.rank()).isEqualTo(4);
        assertThat(BuildStatus.SUPERSEDED.rank()).isEqualTo(4);
        assertThat(BuildStatus.CANCELLED.rank()).isEqualTo(4);
        assertThat(BuildStatus.REJECTED.rank()).isEqualTo(4);
    }

    @Test
    void onlyDispatchedAndRunningHoldASlot() {
        assertThat(Arrays.stream(BuildStatus.values()).filter(BuildStatus::isInFlight))
                .containsExactlyInAnyOrder(BuildStatus.DISPATCHED, BuildStatus.RUNNING);
    }

    @Test
    void expiredIsNotTerminalBecauseALateResultStillMovesIt() {
        assertThat(Arrays.stream(BuildStatus.values()).filter(BuildStatus::isTerminal))
                .containsExactlyInAnyOrder(
                        BuildStatus.SUCCEEDED,
                        BuildStatus.FAILED,
                        BuildStatus.SUPERSEDED,
                        BuildStatus.CANCELLED,
                        BuildStatus.REJECTED);
    }

    @Test
    void triggersMapToPriorityClassesAndUnknownOnesToNothing() {
        assertThat(PriorityClass.of("MANUAL")).contains(PriorityClass.INTERACTIVE);
        assertThat(PriorityClass.of("LINKED")).contains(PriorityClass.INTERACTIVE);
        assertThat(PriorityClass.of("WEBHOOK")).contains(PriorityClass.AUTOMATIC);
        assertThat(PriorityClass.of("RECONCILED")).contains(PriorityClass.AUTOMATIC);
        assertThat(PriorityClass.of("manual")).isEmpty();
        assertThat(PriorityClass.of(null)).isEmpty();
        assertThat(PriorityClass.INTERACTIVE.rank()).isLessThan(PriorityClass.AUTOMATIC.rank());
    }
}
