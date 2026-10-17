package io.pallet.buildqueue.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The org's slots and backlog against its limit profile. */
@Schema(description = "The org's build slots and backlog against its limits.")
public record QueueUsageDto(
        @Schema(description = "Builds the org has in flight (DISPATCHED or RUNNING).", example = "1")
        int inFlight,

        @Schema(description = "Builds the org may have in flight at once.", example = "1")
        int maxConcurrent,

        @Schema(description = "Builds the org has waiting (QUEUED).", example = "3")
        long queued,

        @Schema(description = "Waiting builds the org may have; a push beyond it is REJECTED.", example = "50")
        int maxQueued,

        @Schema(description = "The limit profile the org is on.", example = "default")
        String profile,

        @Schema(description = "Whether an operator has paused dispatching for the whole platform.")
        boolean dispatchPaused) {}
