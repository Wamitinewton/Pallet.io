package io.pallet.orgteam.app;

import io.pallet.common.api.ApiResponse;
import io.pallet.common.api.PageQuery;
import io.pallet.common.api.PageResponse;
import io.pallet.common.error.ErrorResponse;
import io.pallet.orgteam.docs.ApiDocs;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/org-team/orgs/{orgId}/apps")
@Tag(name = "Apps", description = "Register apps and choose where they run.")
class AppController {

    private final AppService appService;
    private final AccessResolver accessResolver;

    AppController(AppService appService, AccessResolver accessResolver) {
        this.appService = appService;
        this.accessResolver = accessResolver;
    }

    @PostMapping
    @PreAuthorize("@access.atLeast(#orgId, 'DEVELOPER')")
    @Operation(
            summary = "Create an app",
            description = "The cloud provider and region are chosen here and can never be changed. The region must be "
                    + "on the allow-list for the provider. " + ApiDocs.DEVELOPER + ApiDocs.RETRY_CONFLICTS,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "400",
                        description = "INVALID_REGION or a validation failure",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "409",
                        description = "SLUG_TAKEN or QUOTA_EXCEEDED",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ResponseEntity<ApiResponse<AppDto>> create(
            @PathVariable String orgId, @Valid @RequestBody CreateAppRequest request) {
        String actor = accessResolver.resolve(orgId).userId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("App created", appService.create(orgId, actor, request)));
    }

    @GetMapping
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    @Operation(
            summary = "List apps",
            description = "Optionally filtered by team or cloud provider. " + ApiDocs.ANY_MEMBER)
    ApiResponse<PageResponse<AppDto>> list(
            @PathVariable String orgId,
            @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) CloudProvider cloudProvider,
            @ModelAttribute PageQuery pageQuery) {
        return ApiResponse.ok("Apps retrieved", appService.list(orgId, teamId, cloudProvider, pageQuery));
    }

    @GetMapping("/{appId}")
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    @Operation(summary = "Get an app", description = ApiDocs.ANY_MEMBER)
    ApiResponse<AppDto> get(@PathVariable String orgId, @PathVariable UUID appId) {
        return ApiResponse.ok("App retrieved", appService.get(orgId, appId));
    }

    @PatchMapping("/{appId}")
    @PreAuthorize("@access.atLeast(#orgId, 'DEVELOPER')")
    @Operation(
            summary = "Update an app",
            description = "Changes the name and the owning team only; sending null for teamId detaches the app. "
                    + "Any other field, including the cloud provider and region, is rejected with 400. "
                    + ApiDocs.DEVELOPER,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "409",
                        description = "CONCURRENT_MODIFICATION",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ApiResponse<AppDto> update(
            @PathVariable String orgId, @PathVariable UUID appId, @Valid @RequestBody UpdateAppRequest request) {
        String actor = accessResolver.resolve(orgId).userId();
        return ApiResponse.ok("App updated", appService.update(orgId, actor, appId, request));
    }

    @DeleteMapping("/{appId}")
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    @Operation(
            summary = "Delete an app",
            description = "Soft delete; the app's slug becomes reusable. " + ApiDocs.ADMIN,
            responses =
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Deleted"))
    ResponseEntity<Void> delete(@PathVariable String orgId, @PathVariable UUID appId) {
        appService.delete(orgId, accessResolver.resolve(orgId).userId(), appId);
        return ResponseEntity.noContent().build();
    }
}
