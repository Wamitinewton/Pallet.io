package io.pallet.orgteam.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.security.SignedTokenTestConfiguration;
import io.pallet.orgteam.token.SignedActionToken;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, SignedTokenTestConfiguration.class})
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class InvitePreviewIntegrationTest extends InviteIntegrationSupport {

    private static final String PREVIEW = "/api/v1/org-team/invites/{token}";

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private String issuedToken(TestOrg org, String email, String role) throws Exception {
        invite(org.orgId(), org.owner(), email, role);
        return tokenOf(acceptUrls(org.orgId()).getLast());
    }

    private UUID inviteIdOf(String orgId) {
        return jdbc.queryForObject("SELECT id FROM org_team.invites WHERE org_id = ?", UUID.class, orgId);
    }

    private String mint(String secret, String purpose, Instant expiresAt, Map<String, String> claims) {
        return SignedActionToken.issue(purpose, claims, Instant.now(), expiresAt, secret);
    }

    private Map<String, String> claimsFor(TestOrg org, String email, String role) {
        return Map.of("jti", inviteIdOf(org.orgId()).toString(), "orgId", org.orgId(), "email", email, "role", role);
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void aRealTokenPreviewsWithoutAnyAuthorizationHeader() throws Exception {
        TestOrg org = newTeamOrg();
        jdbc.update("UPDATE org_team.organizations SET name = 'Acme Robotics' WHERE org_id = ?", org.orgId());
        String token = issuedToken(org, "jane.doe@example.com", "DEVELOPER");

        mvcGet(token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orgName").value("Acme Robotics"))
                .andExpect(jsonPath("$.data.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.data.maskedEmail").value("j***@example.com"))
                .andExpect(jsonPath("$.data.inviterName").value(org.owner()))
                .andExpect(jsonPath("$.data.expiresAt").exists())
                .andExpect(jsonPath("$.data.orgId").doesNotExist())
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(content().string(not(containsString("jane.doe@example.com"))))
                .andExpect(content().string(not(containsString(org.orgId()))))
                .andExpect(content()
                        .string(not(containsString(inviteIdOf(org.orgId()).toString()))));
    }

    @Test
    void previewingRepeatedlyChangesNothing() throws Exception {
        TestOrg org = newTeamOrg();
        String token = issuedToken(org, "jane@example.com", "VIEWER");
        UUID id = inviteIdOf(org.orgId());

        for (int i = 0; i < 3; i++) {
            mvcGet(token).andExpect(status().isOk());
        }

        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.outbox_events WHERE org_id = ?", Integer.class, org.orgId()))
                .isEqualTo(2);
    }

    @Test
    void everyKindOfBadTokenIsTheSame400AndRunsNoSql() throws Exception {
        TestOrg org = newTeamOrg();
        String good = issuedToken(org, "jane@example.com", "VIEWER");
        Map<String, String> claims = claimsFor(org, "jane@example.com", "viewer");
        String secret = signing.signingKey();
        String unsigned = new PlainJWT(new JWTClaimsSet.Builder()
                        .claim("purpose", "invite")
                        .claim("jti", claims.get("jti"))
                        .claim("orgId", org.orgId())
                        .expirationTime(Date.from(Instant.now().plus(Duration.ofHours(1))))
                        .build())
                .serialize();
        List<String> forged = List.of(
                good.substring(0, good.length() - 2) + (good.endsWith("AA") ? "BB" : "AA"),
                mint(
                        "another-secret-that-is-long-enough-for-hs256",
                        "invite",
                        Instant.now().plusSeconds(3600),
                        claims),
                mint(secret, "invite", Instant.now().minusSeconds(5), claims),
                mint(secret, "password-reset", Instant.now().plusSeconds(3600), claims),
                unsigned,
                "not-a-token");

        long before = statistics().getPrepareStatementCount();
        List<String> bodies = new java.util.ArrayList<>();
        for (String token : forged) {
            String body = mvcGet(token)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            bodies.add((String) JsonPath.read(body, "$.message"));
        }

        assertThat(statistics().getPrepareStatementCount()).isEqualTo(before);
        assertThat(bodies).allMatch(bodies.getFirst()::equals);
    }

    @Test
    void aTokenWhoseEmailOrRoleDisagreesWithItsRowIsA400() throws Exception {
        TestOrg org = newTeamOrg();
        issuedToken(org, "jane@example.com", "VIEWER");
        String secret = signing.signingKey();
        Instant later = Instant.now().plusSeconds(3600);

        mvcGet(mint(secret, "invite", later, claimsFor(org, "mallory@example.com", "viewer")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
        mvcGet(mint(secret, "invite", later, claimsFor(org, "jane@example.com", "admin")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
    }

    @Test
    void aTokenNamingAnotherOrgsInviteIsNoLongerValidNotAPreview() throws Exception {
        TestOrg org = newTeamOrg();
        TestOrg other = newTeamOrg();
        issuedToken(org, "jane@example.com", "VIEWER");
        Map<String, String> claims = Map.of(
                "jti",
                inviteIdOf(org.orgId()).toString(),
                "orgId",
                other.orgId(),
                "email",
                "jane@example.com",
                "role",
                "viewer");

        mvcGet(mint(signing.signingKey(), "invite", Instant.now().plusSeconds(3600), claims))
                .andExpect(status().isGone());
    }

    @Test
    void revokedAcceptedAndExpiredInvitesAreGone() throws Exception {
        TestOrg org = newTeamOrg();
        String token = issuedToken(org, "jane@example.com", "VIEWER");
        UUID id = inviteIdOf(org.orgId());

        for (String status : List.of("REVOKED", "ACCEPTED", "EXPIRED")) {
            jdbc.update("UPDATE org_team.invites SET status = ? WHERE id = ?", status, id);
            mvcGet(token)
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.error").value("INVITE_NO_LONGER_VALID"));
        }

        jdbc.update(
                "UPDATE org_team.invites SET status = 'PENDING', expires_at = now() - interval '1 second' WHERE id = ?",
                id);
        mvcGet(token).andExpect(status().isGone());
    }

    @Test
    void anInviteToADeletedOrganizationIsGone() throws Exception {
        TestOrg org = newTeamOrg();
        String token = issuedToken(org, "jane@example.com", "VIEWER");
        jdbc.update("UPDATE org_team.organizations SET status = 'DELETED' WHERE org_id = ?", org.orgId());

        mvcGet(token).andExpect(status().isGone());
    }

    @Test
    void aRemovedInvitersNameIsStillShown() throws Exception {
        TestOrg org = newTeamOrg();
        String admin = fixtures.newMember(org.orgId(), "ADMIN", "ACTIVE");
        invite(org.orgId(), admin, "jane@example.com", "VIEWER");
        String token = tokenOf(acceptUrls(org.orgId()).getLast());
        fixtures.setStatus(org.orgId(), admin, "REMOVED");

        mvcGet(token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inviterName").value(admin));
    }

    private org.springframework.test.web.servlet.ResultActions mvcGet(String token) throws Exception {
        return mvc.perform(get(PREVIEW, token));
    }
}
