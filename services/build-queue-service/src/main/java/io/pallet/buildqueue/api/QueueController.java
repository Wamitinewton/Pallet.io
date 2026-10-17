package io.pallet.buildqueue.api;

import io.pallet.buildqueue.api.dto.QueueViewDto;
import io.pallet.common.api.ApiResponse;
import io.pallet.common.api.PageQuery;
import io.pallet.common.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(QueueController.PATH)
@Tag(
        name = "Queue",
        description = "What the org is building now and what is waiting, with the reason each waiting build "
                + "hasn't started. Other orgs' builds are never shown or counted, except in the platform-wide "
                + "PLATFORM_CAPACITY reason.")
class QueueController {

    static final String PATH = "/build-queue/orgs/{orgId}/queue";

    private final QueueService queue;

    QueueController(QueueService queue) {
        this.queue = queue;
    }

    @GetMapping
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    @Operation(
            summary = "Show the org's build queue",
            description = "The org's active builds (QUEUED, DISPATCHED, RUNNING): builds in flight first, then "
                    + "waiting builds in the order the dispatcher will take them. A waiting build carries position, "
                    + "its 1-based place among the org's waiting builds counted across pages, and waitingOn, the "
                    + "first rule holding it back: APP_BUSY waits for the app's running build to finish; "
                    + "ORG_CONCURRENCY waits for one of the org's builds to finish, or a larger plan later; "
                    + "PLATFORM_CAPACITY and DISPATCH_PAUSED mean the platform is busy or paused and there is "
                    + "nothing to do; NEXT_ROUND means it is about to start. usage holds the org's slots and "
                    + "backlog against its limits. It is a snapshot read in one transaction; poll it to follow "
                    + "the queue. The order is fixed; sort is ignored. "
                    + ApiDocs.ANY_MEMBER,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "200",
                        description = "The queue and the org's usage"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "403",
                        description = ApiDocs.MEMBERSHIP_403,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "404",
                        description = ApiDocs.MEMBERSHIP_404,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ApiResponse<QueueViewDto> queue(@PathVariable String orgId, @ParameterObject @ModelAttribute PageQuery pageQuery) {
        return ApiResponse.ok("Queue retrieved", queue.queue(orgId, Paging.of(pageQuery)));
    }
}
