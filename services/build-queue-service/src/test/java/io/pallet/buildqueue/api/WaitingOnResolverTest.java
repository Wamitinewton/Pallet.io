package io.pallet.buildqueue.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.buildqueue.api.WaitingOnResolver.Snapshot;
import io.pallet.buildqueue.api.dto.WaitingOn;
import io.pallet.common.test.annotations.UnitTest;
import java.util.Set;
import org.junit.jupiter.api.Test;

@UnitTest
class WaitingOnResolverTest {

    private static final String APP = "app-1";
    private static final int GLOBAL = 4;
    private static final int ORG_MAX = 2;

    @Test
    void aBuildNothingHoldsGoesOutOnTheNextRound() {
        assertThat(resolve(snapshot(false, Set.of("app-2"), 1, 3))).isEqualTo(WaitingOn.NEXT_ROUND);
    }

    @Test
    void anotherBuildOfTheAppInFlightHoldsIt() {
        assertThat(resolve(snapshot(false, Set.of(APP), 1, 1))).isEqualTo(WaitingOn.APP_BUSY);
    }

    @Test
    void theOrgAtItsLimitHoldsIt() {
        assertThat(resolve(snapshot(false, Set.of("app-2", "app-3"), ORG_MAX, 2)))
                .isEqualTo(WaitingOn.ORG_CONCURRENCY);
    }

    @Test
    void anOrgAboveALoweredLimitIsStillAtItsLimit() {
        assertThat(resolve(snapshot(false, Set.of("app-2", "app-3", "app-4"), ORG_MAX + 1, 3)))
                .isEqualTo(WaitingOn.ORG_CONCURRENCY);
    }

    @Test
    void aFullPlatformHoldsABuildItsOrgHasRoomFor() {
        assertThat(resolve(snapshot(false, Set.of(), 0, GLOBAL))).isEqualTo(WaitingOn.PLATFORM_CAPACITY);
    }

    @Test
    void pausedBeatsEveryOtherRule() {
        assertThat(resolve(snapshot(true, Set.of(APP), ORG_MAX, GLOBAL))).isEqualTo(WaitingOn.DISPATCH_PAUSED);
        assertThat(resolve(snapshot(true, Set.of(), 0, 0))).isEqualTo(WaitingOn.DISPATCH_PAUSED);
    }

    @Test
    void anAppBusyAtTheOrgAndPlatformCapsIsAppBusy() {
        assertThat(resolve(snapshot(false, Set.of(APP, "app-2"), ORG_MAX, GLOBAL)))
                .isEqualTo(WaitingOn.APP_BUSY);
    }

    @Test
    void theOrgLimitBeatsAFullPlatform() {
        assertThat(resolve(snapshot(false, Set.of("app-2", "app-3"), ORG_MAX, GLOBAL)))
                .isEqualTo(WaitingOn.ORG_CONCURRENCY);
    }

    private static WaitingOn resolve(Snapshot snapshot) {
        return WaitingOnResolver.resolve(APP, snapshot);
    }

    private static Snapshot snapshot(boolean paused, Set<String> appsInFlight, int orgInFlight, long platformInFlight) {
        return new Snapshot(paused, appsInFlight, orgInFlight, ORG_MAX, platformInFlight, GLOBAL);
    }
}
