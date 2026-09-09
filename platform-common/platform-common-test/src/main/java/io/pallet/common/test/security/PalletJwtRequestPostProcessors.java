package io.pallet.common.test.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Stamps a {@code MockMvc} request with a mock JWT shaped like a real Keycloak-issued Pallet
 * token: an {@code org_id} claim plus {@code realm_access.roles}. This lets a
 * {@code @ControllerTest} (or any {@code @WebMvcTest}) for a secured endpoint run without a real
 * Keycloak token or the platform's own {@code SecurityFilterChain} wired into the slice.
 *
 * <p>{@code @ControllerTest} does <strong>not</strong> {@code @Import} the security module's
 * autoconfiguration, unlike {@code PalletErrorHandlingAutoConfiguration}: doing so would make
 * every controller test on the platform require authentication by default, breaking any service
 * that isn't secured. Instead this helper sets the granted authorities directly via
 * {@code .authorities(...)}, mirroring {@code PalletResourceServerAutoConfiguration}'s
 * {@code realm_access.roles -> ROLE_*} mapping. A controller under test asserting
 * {@code hasRole("admin")} sees the same authority it would from a real token, without that
 * converter (or any {@code SecurityFilterChain} bean) needing to be part of the slice's context.
 *
 * <p>The claim name ({@code org_id}) is a literal here, not a reference to
 * {@code io.pallet.common.security.OrgContext#ORG_CLAIM}: {@code platform-common-security} pulls
 * in Spring Security's own default-secure autoconfiguration, and this module cannot depend on it
 * without that bleeding, transitively, into every service that merely adds
 * {@code platform-common-test}, including ones with no security at all. Keep the two in sync by
 * hand if the claim name ever changes.
 */
public final class PalletJwtRequestPostProcessors {

    /** Must match {@code io.pallet.common.security.OrgContext.ORG_CLAIM}. */
    private static final String ORG_CLAIM = "org_id";

    private PalletJwtRequestPostProcessors() {}

    /**
     * A mock JWT for {@code orgId} with no realm roles.
     */
    public static RequestPostProcessor orgJwt(String orgId) {
        return orgJwt(orgId, new String[0]);
    }

    /**
     * A mock JWT for {@code orgId} carrying {@code realmRoles}, both as the
     * {@code realm_access.roles} claim a real token would carry and as {@code ROLE_*} granted
     * authorities so {@code hasRole(...)} checks in the controller under test resolve correctly.
     */
    public static RequestPostProcessor orgJwt(String orgId, String... realmRoles) {
        List<GrantedAuthority> authorities = Stream.of(realmRoles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();

        return jwt().jwt(builder -> {
                    builder.claim(ORG_CLAIM, orgId);
                    builder.claim("realm_access", Map.of("roles", List.of(realmRoles)));
                })
                .authorities(authorities);
    }
}
