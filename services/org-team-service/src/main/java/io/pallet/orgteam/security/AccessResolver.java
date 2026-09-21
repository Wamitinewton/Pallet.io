package io.pallet.orgteam.security;

import io.pallet.common.security.OrgContext;
import io.pallet.orgteam.member.MemberAccess;
import io.pallet.orgteam.member.MembershipRepository;
import io.pallet.orgteam.member.MembershipStatus;
import io.pallet.orgteam.member.Role;
import io.pallet.orgteam.observability.MetricsCatalog;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import io.pallet.orgteam.org.OrgStatus;
import io.pallet.orgteam.security.AccessExceptions.NotAMemberException;
import io.pallet.orgteam.security.AccessExceptions.OrgNotFoundException;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

@Component
public class AccessResolver {

    private static final String REQUEST_ATTRIBUTE = AccessResolver.class.getName() + ".CONTEXT";
    private static final String AUTH_TIME_CLAIM = "auth_time";
    private static final String REALM_ACCESS_CLAIM = "realm_access";

    private final MembershipRepository memberships;
    private final OrgTeamMetrics metrics;

    AccessResolver(MembershipRepository memberships, OrgTeamMetrics metrics) {
        this.memberships = memberships;
        this.metrics = metrics;
    }

    public AccessContext resolve(String pathOrgId) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null
                && request.getAttribute(REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST)
                        instanceof AccessContext memo
                && memo.orgId().equals(pathOrgId)) {
            return memo;
        }
        AccessContext resolved = load(pathOrgId);
        if (request != null) {
            request.setAttribute(REQUEST_ATTRIBUTE, resolved, RequestAttributes.SCOPE_REQUEST);
        }
        return resolved;
    }

    private AccessContext load(String pathOrgId) {
        Optional<String> tokenOrgId = OrgContext.currentOrgId();
        if (tokenOrgId.isEmpty() || !tokenOrgId.get().equals(pathOrgId)) {
            throw denied(MetricsCatalog.DENIED_ORG_MISMATCH, new OrgNotFoundException());
        }
        Jwt jwt = currentJwt();
        String userId = jwt.getSubject();
        if (userId == null) {
            throw denied(MetricsCatalog.DENIED_NOT_A_MEMBER, new NotAMemberException());
        }

        MemberAccess access = memberships
                .findAccess(pathOrgId, userId)
                .orElseThrow(() -> denied(MetricsCatalog.DENIED_ORG_NOT_FOUND, new OrgNotFoundException()));
        if (access.orgStatus() != OrgStatus.ACTIVE) {
            throw denied(MetricsCatalog.DENIED_ORG_NOT_FOUND, new OrgNotFoundException());
        }
        if (access.role() == null || access.membershipStatus() != MembershipStatus.ACTIVE) {
            throw denied(MetricsCatalog.DENIED_NOT_A_MEMBER, new NotAMemberException());
        }

        recordDrift(jwt, access.role());
        return new AccessContext(pathOrgId, userId, access.role(), authTime(jwt));
    }

    private <E extends RuntimeException> E denied(String reason, E exception) {
        metrics.authzDenied(reason);
        return exception;
    }

    private Jwt currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        throw denied(MetricsCatalog.DENIED_NOT_A_MEMBER, new NotAMemberException());
    }

    private static Instant authTime(Jwt jwt) {
        try {
            return jwt.getClaimAsInstant(AUTH_TIME_CLAIM);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private void recordDrift(Jwt jwt, Role localRole) {
        if (!Objects.equals(highestTokenRole(jwt), localRole)) {
            metrics.tokenRoleDrift();
        }
    }

    private static Role highestTokenRole(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS_CLAIM);
        if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> names)) {
            return null;
        }
        return names.stream()
                .map(name -> name instanceof String value ? knownRole(value) : null)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private static Role knownRole(String name) {
        try {
            return Role.fromKeycloakName(name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
