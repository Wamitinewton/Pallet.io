package io.pallet.orgteam.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.api.ApiResponse;
import io.pallet.common.security.RevokedSessionRegistry;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@IntegrationTest
@AutoConfigureMockMvc
@Import({
    RedisTestContainerConfiguration.class,
    SignedTokenTestConfiguration.class,
    AuthorizationWebIntegrationTest.ProbeController.class
})
class AuthorizationWebIntegrationTest {

    private static final String PROBE = "/api/v1/org-team-probe/orgs/{orgId}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RevokedSessionRegistry revokedSessions;

    private OrgFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new OrgFixtures(jdbc);
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanUp();
    }

    @Test
    void anActiveMemberWithEnoughRoleIsServed() throws Exception {
        String orgId = fixtures.newActiveOrg();
        String userId = fixtures.newMember(orgId, "ADMIN", "ACTIVE");

        call("/admin", orgId, OrgTeamTestTokens.forMember(orgId, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("ADMIN"));
    }

    @Test
    void aRoleBelowTheEndpointRequirementIsInsufficientRole() throws Exception {
        String orgId = fixtures.newActiveOrg();
        String userId = fixtures.newMember(orgId, "VIEWER", "ACTIVE");

        call("/admin", orgId, OrgTeamTestTokens.forMember(orgId, userId).claimingRoles("owner"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void aDemotionTakesEffectOnTheNextRequestWithTheSameToken() throws Exception {
        String orgId = fixtures.newActiveOrg();
        String userId = fixtures.newMember(orgId, "ADMIN", "ACTIVE");
        String token = OrgTeamTestTokens.forMember(orgId, userId)
                .claimingRoles("admin")
                .signed();

        callWithToken("/admin", orgId, token).andExpect(status().isOk());
        fixtures.setRole(orgId, userId, "VIEWER");

        callWithToken("/admin", orgId, token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));
    }

    @Test
    void aRemovalTakesEffectOnTheNextRequestWithTheSameToken() throws Exception {
        String orgId = fixtures.newActiveOrg();
        String userId = fixtures.newMember(orgId, "DEVELOPER", "ACTIVE");
        String token = OrgTeamTestTokens.forMember(orgId, userId)
                .claimingRoles("developer")
                .signed();

        callWithToken("/viewer", orgId, token).andExpect(status().isOk());
        fixtures.setStatus(orgId, userId, "REMOVED");

        callWithToken("/viewer", orgId, token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("NOT_A_MEMBER"));
    }

    @Test
    void aRemovedMemberIsNotAMember() throws Exception {
        String orgId = fixtures.newActiveOrg();
        String userId = fixtures.newMember(orgId, "OWNER", "REMOVED");

        call("/viewer", orgId, OrgTeamTestTokens.forMember(orgId, userId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("NOT_A_MEMBER"));
    }

    @Test
    void anotherOrgsPathIsNotFoundWithTheSameBodyAsAnUnknownOrg() throws Exception {
        String ownOrg = fixtures.newActiveOrg();
        String otherOrg = fixtures.newActiveOrg();
        String userId = fixtures.newMember(ownOrg, "OWNER", "ACTIVE");
        OrgTeamTestTokens token = OrgTeamTestTokens.forMember(ownOrg, userId);

        call("/viewer", otherOrg, token)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ORG_NOT_FOUND"));
        call("/viewer", "org-that-does-not-exist", token)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ORG_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Organization not found."));
    }

    @Test
    void aDeletedOrgIsNotFoundEvenForItsOwner() throws Exception {
        String orgId = fixtures.newOrg("DELETED");
        String userId = fixtures.newMember(orgId, "OWNER", "ACTIVE");

        call("/viewer", orgId, OrgTeamTestTokens.forMember(orgId, userId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ORG_NOT_FOUND"));
    }

    @Test
    void recentAuthenticationLetsAFreshTokenThrough() throws Exception {
        String orgId = fixtures.newActiveOrg();
        String userId = fixtures.newMember(orgId, "OWNER", "ACTIVE");

        callDelete(orgId, OrgTeamTestTokens.forMember(orgId, userId).authenticatedAt(Instant.now()))
                .andExpect(status().isOk());
    }

    @Test
    void aStaleAuthTimeIsReauthenticationRequired() throws Exception {
        String orgId = fixtures.newActiveOrg();
        String userId = fixtures.newMember(orgId, "OWNER", "ACTIVE");

        callDelete(
                        orgId,
                        OrgTeamTestTokens.forMember(orgId, userId)
                                .authenticatedAt(Instant.now().minusSeconds(3600)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("REAUTHENTICATION_REQUIRED"));
    }

    @Test
    void aMissingAuthTimeIsReauthenticationRequired() throws Exception {
        String orgId = fixtures.newActiveOrg();
        String userId = fixtures.newMember(orgId, "OWNER", "ACTIVE");

        callDelete(orgId, OrgTeamTestTokens.forMember(orgId, userId).withoutAuthTime())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("REAUTHENTICATION_REQUIRED"));
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mvc.perform(get(PROBE + "/viewer", "org-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void anUnparseableTokenIsUnauthorized() throws Exception {
        callWithToken("/viewer", "org-1", "not-a-jwt")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void aTokenWhoseSessionWasRevokedIsUnauthorizedThroughTheRedisRegistry() throws Exception {
        String orgId = fixtures.newActiveOrg();
        String userId = fixtures.newMember(orgId, "OWNER", "ACTIVE");
        OrgTeamTestTokens token = OrgTeamTestTokens.forMember(orgId, userId);

        call("/viewer", orgId, token).andExpect(status().isOk());
        revokedSessions.revoke(token.sessionId(), Duration.ofMinutes(5));

        call("/viewer", orgId, token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTHENTICATION_REQUIRED"));
    }

    private ResultActions call(String path, String orgId, OrgTeamTestTokens token) throws Exception {
        return callWithToken(path, orgId, token.signed());
    }

    private ResultActions callWithToken(String path, String orgId, String token) throws Exception {
        return mvc.perform(get(PROBE + path, orgId).header("Authorization", "Bearer " + token));
    }

    private ResultActions callDelete(String orgId, OrgTeamTestTokens token) throws Exception {
        return mvc.perform(delete(PROBE, orgId).header("Authorization", "Bearer " + token.signed()));
    }

    @RestController
    @RequestMapping("/org-team-probe/orgs/{orgId}")
    static class ProbeController {

        private final AccessResolver resolver;
        private final RecentAuthentication recentAuthentication;

        ProbeController(AccessResolver resolver, RecentAuthentication recentAuthentication) {
            this.resolver = resolver;
            this.recentAuthentication = recentAuthentication;
        }

        @GetMapping("/viewer")
        @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
        ApiResponse<String> viewer(@PathVariable String orgId) {
            return ApiResponse.ok("ok", resolver.resolve(orgId).role().name());
        }

        @GetMapping("/admin")
        @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
        ApiResponse<String> admin(@PathVariable String orgId) {
            return ApiResponse.ok("ok", resolver.resolve(orgId).role().name());
        }

        @DeleteMapping
        @PreAuthorize("@access.isOwner(#orgId)")
        ApiResponse<Void> destroy(@PathVariable String orgId) {
            recentAuthentication.require(resolver.resolve(orgId));
            return ApiResponse.ok("ok");
        }
    }
}
