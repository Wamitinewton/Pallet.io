package io.pallet.common.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Reads the {@code org_id} claim off the current access token.
 *
 * <p>Pallet runs one Keycloak realm for the whole platform with an org id baked
 * into every token (see {@code docs/adr/0003-single-keycloak-realm.md}). Every
 * service that touches tenant data checks this claim against the resource being
 * requested; that same check is what makes tenant log isolation possible.
 */
public final class OrgContext {

    /**
     * Claim name carrying the caller's organization id.
     */
    public static final String ORG_CLAIM = "org_id";

    private OrgContext() {}

    /**
     * @return the caller's org id
     * @throws IllegalStateException if there is no authenticated JWT, or it
     *                               carries no {@code org_id} claim
     */
    public static String requireOrgId() {
        return currentOrgId()
                .orElseThrow(() -> new IllegalStateException("No " + ORG_CLAIM + " claim on the current token"));
    }

    public static Optional<String> currentOrgId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        return Optional.ofNullable(jwt.getClaimAsString(ORG_CLAIM));
    }
}
