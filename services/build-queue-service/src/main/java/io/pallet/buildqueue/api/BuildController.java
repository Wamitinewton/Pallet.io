package io.pallet.buildqueue.api;

import io.pallet.buildqueue.api.dto.BuildDto;
import io.pallet.buildqueue.api.dto.BuildMapper;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.cancel.CancellationService;
import io.pallet.buildqueue.security.AccessResolver;
import io.pallet.common.api.ApiResponse;
import io.pallet.common.api.PageQuery;
import io.pallet.common.api.PageResponse;
import io.pallet.common.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Set;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(BuildController.PATH)
@Tag(
        name = "Builds",
        description = "An app's build history, one build, and taking a waiting build out of the queue. There is no "
                + "endpoint to enqueue a build: builds come from pushes, and a manual build is requested from "
                + "git-integration-service's manual build endpoint, which resolves the branch head on GitHub first.")
class BuildController {

    static final String PATH = "/build-queue/orgs/{orgId}";

    private final QueueService queue;
    private final CancellationService cancellations;
    private final AccessResolver accessResolver;

    BuildController(QueueService queue, CancellationService cancellations, AccessResolver accessResolver) {
        this.queue = queue;
        this.cancellations = cancellations;
        this.accessResolver = accessResolver;
    }

    @GetMapping("/apps/{appId}/builds")
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    @Operation(
            summary = "List an app's builds",
            description = "The app's builds in every status, newest first, optionally narrowed to one or more "
                    + "statuses. A deleted app's history stays readable until retention removes it. The order is "
                    + "fixed; sort is ignored. "
                    + ApiDocs.ANY_MEMBER,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "200",
                        description = "One page of the app's builds"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "400",
                        description = "An unknown status value",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "403",
                        description = ApiDocs.MEMBERSHIP_403,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "404",
                        description = "APP_NOT_FOUND, " + ApiDocs.MEMBERSHIP_404,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ApiResponse<PageResponse<BuildDto>> history(
            @PathVariable String orgId,
            @PathVariable String appId,
            @Parameter(
                            description = "Only builds in these statuses; repeat it or separate values with commas. "
                                    + "Absent means every status.")
                    @RequestParam(name = "status", defaultValue = "")
                    Set<BuildStatus> statuses,
            @ParameterObject @ModelAttribute PageQuery pageQuery) {
        return ApiResponse.ok("Builds retrieved", queue.history(orgId, appId, statuses, Paging.of(pageQuery)));
    }

    @GetMapping("/builds/{buildId}")
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    @Operation(
            summary = "Get a build",
            description = "One build of the org, with why it reached its status, what superseded it, how many "
                    + "times it was dispatched, and its timestamps. "
                    + ApiDocs.ANY_MEMBER,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The build"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "403",
                        description = ApiDocs.MEMBERSHIP_403,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "404",
                        description = "BUILD_NOT_FOUND, " + ApiDocs.MEMBERSHIP_404,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ApiResponse<BuildDto> build(@PathVariable String orgId, @PathVariable UUID buildId) {
        return ApiResponse.ok("Build retrieved", queue.build(orgId, buildId));
    }

    @PostMapping("/builds/{buildId}/cancel")
    @PreAuthorize("@access.atLeast(#orgId, 'DEVELOPER')")
    @Operation(
            summary = "Cancel a waiting build",
            description = "Takes a QUEUED build out of the queue; it becomes CANCELLED with statusReason "
                    + "USER_CANCELLED. Idempotent: cancelling a build you or another member already cancelled "
                    + "returns it unchanged. Only a waiting build can be cancelled: one that has been dispatched "
                    + "or started can't be cancelled yet. "
                    + ApiDocs.DEVELOPER,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "200",
                        description = "Cancelled, or already cancelled by a person"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "403",
                        description = ApiDocs.MEMBERSHIP_403,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "404",
                        description = "BUILD_NOT_FOUND, " + ApiDocs.MEMBERSHIP_404,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "409",
                        description = "BUILD_NOT_CANCELLABLE: the build is no longer QUEUED; meta.status is its "
                                + "current status",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ApiResponse<BuildDto> cancel(@PathVariable String orgId, @PathVariable UUID buildId) {
        String userId = accessResolver.resolve(orgId).userId();
        return ApiResponse.ok("Build cancelled", BuildMapper.toDto(cancellations.cancelByUser(orgId, buildId, userId)));
    }
}
