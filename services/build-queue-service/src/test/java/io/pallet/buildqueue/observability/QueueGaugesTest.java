package io.pallet.buildqueue.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.buildqueue.config.BuildQueueProperties.Limits;
import io.pallet.buildqueue.config.BuildQueueProperties.Profile;
import io.pallet.buildqueue.observability.QueueGauges.OrgActivity;
import io.pallet.buildqueue.observability.QueueGauges.Snapshot;
import io.pallet.common.test.annotations.UnitTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@UnitTest
class QueueGaugesTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
    private static final Limits LIMITS = new Limits(
            "default", Map.of("default", new Profile(1, 50), "team", new Profile(3, 200)), Map.of("org-team", "team"));

    @Test
    void depthAndInFlightAreSummedOverEveryOrg() {
        Snapshot snapshot = QueueGauges.summarize(
                List.of(activity("org-a", 2, 1, 1, 0, ago(30), ago(30)), activity("org-b", 0, 3, 0, 2, ago(10), null)),
                LIMITS);

        assertThat(snapshot.interactive()).isEqualTo(2);
        assertThat(snapshot.automatic()).isEqualTo(4);
        assertThat(snapshot.dispatched()).isEqualTo(1);
        assertThat(snapshot.running()).isEqualTo(2);
        assertThat(snapshot.oldestQueuedAgeSeconds()).isEqualTo(30);
    }

    @Test
    void anOrgAtItsConcurrencyLimitHasNothingDispatchableHoweverLongItWaits() {
        Snapshot snapshot = QueueGauges.summarize(
                List.of(
                        activity("org-full", 0, 1, 0, 1, ago(600), ago(600)),
                        activity("org-free", 0, 1, 0, 0, ago(20), ago(20))),
                LIMITS);

        assertThat(snapshot.oldestQueuedAgeSeconds()).isEqualTo(600);
        assertThat(snapshot.oldestDispatchableAgeSeconds()).isEqualTo(20);
    }

    @Test
    void anOrgsOwnProfileDecidesWhetherItsBuildsAreDispatchable() {
        Snapshot snapshot = QueueGauges.summarize(List.of(activity("org-team", 0, 1, 1, 1, ago(90), ago(90))), LIMITS);

        assertThat(snapshot.oldestDispatchableAgeSeconds()).isEqualTo(90);
    }

    @Test
    void aBuildWaitingOnlyBehindItsOwnAppIsNotDispatchable() {
        Snapshot snapshot = QueueGauges.summarize(List.of(activity("org-a", 0, 1, 0, 0, ago(400), null)), LIMITS);

        assertThat(snapshot.oldestQueuedAgeSeconds()).isEqualTo(400);
        assertThat(snapshot.oldestDispatchableAgeSeconds()).isZero();
    }

    @Test
    void anEmptyQueueReportsZeroEverywhere() {
        assertThat(QueueGauges.summarize(List.of(), LIMITS)).isEqualTo(Snapshot.EMPTY);
    }

    private static OrgActivity activity(
            String orgId,
            long interactive,
            long automatic,
            long dispatched,
            long running,
            Instant oldest,
            Instant oldestAppFree) {
        return new OrgActivity(orgId, interactive, automatic, dispatched, running, oldest, oldestAppFree, NOW);
    }

    private static Instant ago(long seconds) {
        return NOW.minus(Duration.ofSeconds(seconds));
    }
}
