package io.pallet.buildqueue.api.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * An active build in the queue view. {@code position} (1-based among the org's waiting builds) and {@code waitingOn}
 * are set for a waiting build only.
 */
@Schema(description = "An active build: every field of a build, plus its place in line while it waits.")
public record QueueEntryDto(
        @JsonUnwrapped BuildDto build,

        @Schema(
                nullable = true,
                description = "1-based place among the org's waiting builds, counted across pages; null for a "
                        + "build in flight.",
                example = "2")
        Integer position,

        @Schema(nullable = true, description = QueueEntryDto.WAITING_ON_MEANINGS)
        WaitingOn waitingOn) {

    static final String WAITING_ON_MEANINGS = "The first rule holding a waiting build back; null for a build in "
            + "flight. DISPATCH_PAUSED: an operator paused dispatching; nothing to do but wait. APP_BUSY: another "
            + "build of this app is in flight; it starts when that one finishes. ORG_CONCURRENCY: the org is "
            + "running as many builds at once as its limits allow; it starts when one of them finishes, and a "
            + "larger plan will raise the limit once plans exist. PLATFORM_CAPACITY: the "
            + "platform is at capacity; nothing to do but wait. NEXT_ROUND: nothing is holding it; it starts on "
            + "the next dispatch round.";

    public static QueueEntryDto inFlight(BuildDto build) {
        return new QueueEntryDto(build, null, null);
    }

    public static QueueEntryDto waiting(BuildDto build, int position, WaitingOn waitingOn) {
        return new QueueEntryDto(build, position, waitingOn);
    }
}
