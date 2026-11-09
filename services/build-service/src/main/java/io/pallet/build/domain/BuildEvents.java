package io.pallet.build.domain;

import io.pallet.build.domain.BuildIds.EventKind;
import io.pallet.common.events.BuildFailed;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** The only place build events are made. Event ids come from {@link BuildIds}. */
@Component
public class BuildEvents {

    private final Clock clock;

    BuildEvents(Clock clock) {
        this.clock = clock;
    }

    /** A dispatch for a deleted app or org: the build never started, so no step failed and no time was spent. */
    public BuildFailed failedAtIntake(NewBuild build, FailureCode code) {
        return BuildFailed.of(
                BuildIds.eventId(build.buildId(), EventKind.FAILED),
                build.orgId(),
                clock.instant(),
                new BuildFailed.Failed(
                        build.buildId(),
                        build.appId(),
                        build.commitSha(),
                        build.pushEventId(),
                        code.reason(),
                        build.branch(),
                        code.wireName(),
                        null,
                        0L));
    }
}
