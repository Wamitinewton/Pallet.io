package io.pallet.orgteam.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.security.SignedTokenTestConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, SignedTokenTestConfiguration.class})
class InviteCompatibilityIntegrationTest extends InviteIntegrationSupport {

    @Test
    void aTokenMintedHereVerifiesWithIdentityServicesImplementation() throws Exception {
        TestOrg org = newTeamOrg();
        invite(org.orgId(), org.owner(), "Jane@Example.com", "DEVELOPER").andExpect(status().isCreated());
        String token = tokenOf(acceptUrls(org.orgId()).get(0));

        Map<String, String> claims = IdentityActionTokenVerifier.verify("invite", token, signing.signingKey());

        String inviteId = jdbc.queryForObject(
                "SELECT id::text FROM org_team.invites WHERE org_id = ?", String.class, org.orgId());
        assertThat(claims)
                .containsEntry("jti", inviteId)
                .containsEntry("orgId", org.orgId())
                .containsEntry("email", "jane@example.com")
                .containsEntry("role", "developer")
                .containsEntry("purpose", "invite")
                .containsKey("iat");
    }
}
