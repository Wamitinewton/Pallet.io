package io.pallet.gitintegration.support;

import io.pallet.common.api.ApiResponse;
import io.pallet.gitintegration.security.AccessResolver;
import io.pallet.gitintegration.security.ResourceScope;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** One endpoint per role floor and per scoped resource. {@code @TestComponent} keeps it out of scanning; import it. */
@TestComponent
@RestController
@RequestMapping(ProbeController.BASE)
public class ProbeController {

    public static final String BASE = "/git-integration/probe";
    public static final String ORG = BASE + "/orgs/{orgId}";

    private final AccessResolver resolver;
    private final ResourceScope scope;

    ProbeController(AccessResolver resolver, ResourceScope scope) {
        this.resolver = resolver;
        this.scope = scope;
    }

    @GetMapping("/caller")
    ApiResponse<String> caller() {
        return ApiResponse.ok("ok", resolver.caller().userId());
    }

    @GetMapping("/orgs/{orgId}/viewer")
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    ApiResponse<String> viewer(@PathVariable String orgId) {
        return role(orgId);
    }

    @GetMapping("/orgs/{orgId}/developer")
    @PreAuthorize("@access.atLeast(#orgId, 'DEVELOPER')")
    ApiResponse<String> developer(@PathVariable String orgId) {
        return role(orgId);
    }

    @GetMapping("/orgs/{orgId}/admin")
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    ApiResponse<String> admin(@PathVariable String orgId) {
        return role(orgId);
    }

    @GetMapping("/orgs/{orgId}/owner")
    @PreAuthorize("@access.atLeast(#orgId, 'OWNER')")
    ApiResponse<String> owner(@PathVariable String orgId) {
        return role(orgId);
    }

    @GetMapping("/orgs/{orgId}/apps/{appId}")
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    ApiResponse<UUID> app(@PathVariable String orgId, @PathVariable UUID appId) {
        scope.requireApp(orgId, appId);
        return ApiResponse.ok("ok", appId);
    }

    @GetMapping("/orgs/{orgId}/installations/{installationId}")
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    ApiResponse<Long> installation(@PathVariable String orgId, @PathVariable long installationId) {
        scope.requireInstallation(orgId, installationId);
        return ApiResponse.ok("ok", installationId);
    }

    private ApiResponse<String> role(String orgId) {
        return ApiResponse.ok("ok", resolver.resolve(orgId).role().name());
    }
}
