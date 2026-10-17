package io.pallet.buildqueue.api.dto;

import io.pallet.common.api.PageResponse;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        description = "A snapshot of the org's active builds: usage and entries are read together, so they agree "
                + "with each other. It changes as builds move; poll it.")
public record QueueViewDto(
        QueueUsageDto usage,

        @Schema(description = "Builds in flight first, then waiting builds in the order they will be dispatched.")
        PageResponse<QueueEntryDto> builds) {}
