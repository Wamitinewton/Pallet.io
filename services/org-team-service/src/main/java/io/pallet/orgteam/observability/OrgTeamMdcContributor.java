package io.pallet.orgteam.observability;

import io.pallet.common.observability.MdcContributor;
import io.pallet.common.security.OrgContext;
import java.util.HashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
class OrgTeamMdcContributor implements MdcContributor {

    static final String ORG_ID = "orgId";
    static final String USER_ID = "userId";

    @Override
    public Map<String, String> contribute() {
        Map<String, String> keys = new HashMap<>();
        OrgContext.currentOrgId().ifPresent(orgId -> keys.put(ORG_ID, orgId));
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken) {
            keys.put(USER_ID, authentication.getName());
        }
        return keys;
    }
}
