package io.pallet.gitintegration.security;

import io.pallet.gitintegration.security.AccessExceptions.InsufficientRoleException;
import org.springframework.stereotype.Component;

/**
 * {@code @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")}. Throws rather than returning false, so the gate's
 * {@code 404}/{@code 403} reach the client instead of Spring's generic access denied.
 */
@Component("access")
public class AccessEvaluator {

    private final AccessResolver resolver;

    AccessEvaluator(AccessResolver resolver) {
        this.resolver = resolver;
    }

    public boolean atLeast(String orgId, String role) {
        if (!resolver.resolve(orgId).role().atLeast(Role.valueOf(role))) {
            throw resolver.denied(AccessResolver.REASON_INSUFFICIENT_ROLE, new InsufficientRoleException());
        }
        return true;
    }
}
