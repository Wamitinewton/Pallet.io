package io.pallet.common.test.annotations.fixtures;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Throwaway secured controller proving {@code PalletJwtRequestPostProcessors.orgJwt(...)} stamps
 * a mock request with a {@link Jwt} principal carrying {@code org_id} and {@code ROLE_*}
 * authorities, the same shape a real Keycloak token would produce.
 */
@RestController
public class SecuredTestController {

    @GetMapping("/secured/whoami")
    public Map<String, Object> whoami(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        return Map.of(
                "orgId",
                jwt.getClaimAsString("org_id"),
                "roles",
                authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .toList());
    }
}
