package io.pallet.buildqueue.support;

import io.pallet.buildqueue.security.AccessResolver;
import io.pallet.buildqueue.security.ResourceScope;
import io.pallet.common.api.ApiResponse;
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
@RequestMapping(ProbeController.ORG)
public class ProbeController {

    public static final String ORG = "/build-queue/probe/orgs/{orgId}";

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

    @GetMapping("/apps/{appId}")
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    ApiResponse<String> app(@PathVariable String orgId, @PathVariable String appId) {
        scope.requireApp(orgId, appId);
        return ApiResponse.ok("ok", appId);
    }

    @GetMapping("/builds/{buildId}")
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    ApiResponse<UUID> build(@PathVariable String orgId, @PathVariable UUID buildId) {
        return ApiResponse.ok("ok", scope.requireBuild(orgId, buildId).buildId());
    }

    private ApiResponse<String> role(String orgId) {
        return ApiResponse.ok("ok", resolver.resolve(orgId).role().name());
    }
}
