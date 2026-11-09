package io.pallet.build.support;

import io.pallet.build.security.AccessResolver;
import io.pallet.build.security.ResourceScope;
import io.pallet.common.api.ApiResponse;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** One endpoint per role floor and per scoped resource. {@code @TestComponent} keeps it out of scanning; import it. */
@TestComponent
@RestController
@RequestMapping(ProbeController.ORG)
public class ProbeController {

    public static final String ORG = "/build/probe/orgs/{orgId}";

    private final AccessResolver resolver;
    private final ResourceScope scope;

    ProbeController(AccessResolver resolver, ResourceScope scope) {
        this.resolver = resolver;
        this.scope = scope;
    }

    @GetMapping("/viewer")
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    ApiResponse<String> viewer(@PathVariable String orgId) {
        return role(orgId);
    }

    @GetMapping("/developer")
    @PreAuthorize("@access.atLeast(#orgId, 'DEVELOPER')")
    ApiResponse<String> developer(@PathVariable String orgId) {
        return role(orgId);
    }

    @GetMapping("/admin")
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    ApiResponse<String> admin(@PathVariable String orgId) {
        return role(orgId);
    }

    @GetMapping("/owner")
    @PreAuthorize("@access.atLeast(#orgId, 'OWNER')")
    ApiResponse<String> owner(@PathVariable String orgId) {
        return role(orgId);
    }

    @GetMapping("/apps/{appId}")
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    ApiResponse<String> app(@PathVariable String orgId, @PathVariable String appId) {
        scope.requireApp(orgId, appId);
        return ApiResponse.ok("ok", appId);
    }

    @PutMapping("/apps/{appId}")
    @PreAuthorize("@access.atLeast(#orgId, 'DEVELOPER')")
    ApiResponse<String> activeApp(@PathVariable String orgId, @PathVariable String appId) {
        scope.requireActiveApp(orgId, appId);
        return ApiResponse.ok("ok", appId);
    }

    @GetMapping("/builds/{buildId}")
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    ApiResponse<String> build(@PathVariable String orgId, @PathVariable String buildId) {
        return ApiResponse.ok("ok", scope.requireBuild(orgId, buildId).buildId());
    }

    private ApiResponse<String> role(String orgId) {
        return ApiResponse.ok("ok", resolver.resolve(orgId).role().name());
    }
}
