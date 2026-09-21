package io.pallet.orgteam.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.UnitTest;
import io.pallet.orgteam.security.OrgTeamTestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@UnitTest
class OrgTeamMdcContributorTest {

    private final OrgTeamMdcContributor contributor = new OrgTeamMdcContributor();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anAuthenticatedRequestContributesItsOrgAndUser() {
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(
                        OrgTeamTestTokens.forMember("org-1", "user-1").jwt()));

        assertThat(contributor.contribute())
                .containsEntry(OrgTeamMdcContributor.ORG_ID, "org-1")
                .containsEntry(OrgTeamMdcContributor.USER_ID, "user-1");
    }

    @Test
    void anUnauthenticatedScopeContributesNothing() {
        assertThat(contributor.contribute()).isEmpty();
    }
}
