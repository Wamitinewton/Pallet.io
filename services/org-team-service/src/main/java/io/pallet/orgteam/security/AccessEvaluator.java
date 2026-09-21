package io.pallet.orgteam.security;

import io.pallet.orgteam.member.Role;
import io.pallet.orgteam.observability.MetricsCatalog;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import io.pallet.orgteam.security.AccessExceptions.InsufficientRoleException;
import org.springframework.stereotype.Component;

@Component("access")
public class AccessEvaluator {

    private final AccessResolver resolver;
    private final OrgTeamMetrics metrics;

    AccessEvaluator(AccessResolver resolver, OrgTeamMetrics metrics) {
        this.resolver = resolver;
        this.metrics = metrics;
    }

    public boolean atLeast(String orgId, String role) {
        if (!resolver.resolve(orgId).role().atLeast(Role.valueOf(role))) {
            metrics.authzDenied(MetricsCatalog.DENIED_INSUFFICIENT_ROLE);
            throw new InsufficientRoleException();
        }
        return true;
    }

    public boolean isOwner(String orgId) {
        return atLeast(orgId, Role.OWNER.name());
    }
}
