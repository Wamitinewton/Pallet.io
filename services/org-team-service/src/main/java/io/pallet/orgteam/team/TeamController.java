package io.pallet.orgteam.team;

import io.pallet.common.api.ApiResponse;
import io.pallet.common.api.PageQuery;
import io.pallet.common.api.PageResponse;
import io.pallet.common.error.ErrorResponse;
import io.pallet.orgteam.docs.ApiDocs;
import io.pallet.orgteam.member.MemberDto;
import io.pallet.orgteam.security.AccessResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/org-team/orgs/{orgId}/teams")
@Tag(name = "Teams", description = "Group organization members into teams.")
class TeamController {

    private final TeamService teamService;
    private final AccessResolver accessResolver;

    TeamController(TeamService teamService, AccessResolver accessResolver) {
        this.teamService = teamService;
        this.accessResolver = accessResolver;
    }

    @PostMapping
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    @Operation(
            summary = "Create a team",
            description = "The slug is derived from the name when omitted. " + ApiDocs.ADMIN + ApiDocs.RETRY_CONFLICTS,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "409",
                        description = "SLUG_TAKEN or QUOTA_EXCEEDED",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ResponseEntity<ApiResponse<TeamDto>> create(
            @PathVariable String orgId, @Valid @RequestBody CreateTeamRequest request) {
        String actor = accessResolver.resolve(orgId).userId();
        TeamDto team = teamService.create(orgId, actor, request.name(), request.slug());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Team created", team));
    }

    @GetMapping
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    @Operation(summary = "List teams", description = ApiDocs.ANY_MEMBER)
    ApiResponse<PageResponse<TeamDto>> list(@PathVariable String orgId, @ModelAttribute PageQuery pageQuery) {
        return ApiResponse.ok("Teams retrieved", teamService.list(orgId, pageQuery));
    }

    @GetMapping("/{teamId}")
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    @Operation(summary = "Get a team", description = ApiDocs.ANY_MEMBER)
    ApiResponse<TeamDto> get(@PathVariable String orgId, @PathVariable UUID teamId) {
        return ApiResponse.ok("Team retrieved", teamService.get(orgId, teamId));
    }

    @PatchMapping("/{teamId}")
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    @Operation(summary = "Rename a team", description = "Changes the name only. " + ApiDocs.ADMIN)
    ApiResponse<TeamDto> rename(
            @PathVariable String orgId, @PathVariable UUID teamId, @Valid @RequestBody UpdateTeamRequest request) {
        String actor = accessResolver.resolve(orgId).userId();
        return ApiResponse.ok("Team updated", teamService.rename(orgId, actor, teamId, request.name()));
    }

    @DeleteMapping("/{teamId}")
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    @Operation(
            summary = "Delete a team",
            description = "Detaches the team's apps and removes its member assignments; the members stay in the "
                    + "organization. " + ApiDocs.ADMIN,
            responses =
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Deleted"))
    ResponseEntity<Void> delete(@PathVariable String orgId, @PathVariable UUID teamId) {
        teamService.delete(orgId, accessResolver.resolve(orgId).userId(), teamId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{teamId}/members")
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    @Operation(summary = "List a team's members", description = ApiDocs.ANY_MEMBER)
    ApiResponse<PageResponse<MemberDto>> listMembers(
            @PathVariable String orgId, @PathVariable UUID teamId, @ModelAttribute PageQuery pageQuery) {
        return ApiResponse.ok("Team members retrieved", teamService.listMembers(orgId, teamId, pageQuery));
    }

    @PostMapping("/{teamId}/members")
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    @Operation(
            summary = "Add a member to a team",
            description = "Assigns an existing active organization member; it does not invite anyone. " + ApiDocs.ADMIN
                    + ApiDocs.RETRY_CONFLICTS,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "409",
                        description = "ALREADY_IN_TEAM",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ResponseEntity<ApiResponse<MemberDto>> addMember(
            @PathVariable String orgId, @PathVariable UUID teamId, @Valid @RequestBody AddTeamMemberRequest request) {
        String actor = accessResolver.resolve(orgId).userId();
        MemberDto member = teamService.addMember(orgId, actor, teamId, request.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Team member added", member));
    }

    @DeleteMapping("/{teamId}/members/{userId}")
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    @Operation(
            summary = "Remove a member from a team",
            description = ApiDocs.ADMIN,
            responses =
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Removed"))
    ResponseEntity<Void> removeMember(
            @PathVariable String orgId, @PathVariable UUID teamId, @PathVariable String userId) {
        teamService.removeMember(orgId, accessResolver.resolve(orgId).userId(), teamId, userId);
        return ResponseEntity.noContent().build();
    }
}
