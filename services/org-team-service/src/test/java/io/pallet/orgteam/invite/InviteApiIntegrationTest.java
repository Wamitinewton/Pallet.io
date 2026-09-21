package io.pallet.orgteam.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.security.SignedTokenTestConfiguration;
import io.pallet.orgteam.token.SignedActionToken;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

@IntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import({RedisTestContainerConfiguration.class, SignedTokenTestConfiguration.class})
class InviteApiIntegrationTest extends InviteIntegrationSupport {

    private UUID createdId(TestOrg org, String actor, String email, String role) throws Exception {
        String id = JsonPath.read(
                invite(org.orgId(), actor, email, role)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.data.id");
        return UUID.fromString(id);
    }

    private void backdateLastSend(UUID inviteId) {
        jdbc.update("UPDATE org_team.invites SET last_sent_at = now() - interval '10 minutes' WHERE id = ?", inviteId);
    }

    @Test
    void createPersistsAPendingInviteAndReturnsItWithoutTheToken() throws Exception {
        TestOrg org = newTeamOrg();

        invite(org.orgId(), org.owner(), "Jane@Example.com", "DEVELOPER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.email").value("jane@example.com"))
                .andExpect(jsonPath("$.data.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.invitedByUserId").value(org.owner()))
                .andExpect(jsonPath("$.data.sendCount").value(1))
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.acceptUrl").doesNotExist())
                .andExpect(content().string(not(containsString("eyJ"))));

        assertThat(jdbc.queryForList("SELECT status FROM org_team.invites WHERE org_id = ?", String.class, org.orgId()))
                .containsExactly("PENDING");
    }

    @Test
    void theOutboxHoldsTheSensitiveNotificationThenTheAuditAndTheTokenMatchesTheInvite() throws Exception {
        TestOrg org = newTeamOrg();
        UUID id = createdId(org, org.owner(), "jane@example.com", "ADMIN");

        List<Map<String, Object>> events = outbox(org.orgId());

        assertThat(events)
                .extracting(e -> e.get("event_type"))
                .containsExactly("notification.requested", "audit.event.recorded");
        assertThat(events.get(0).get("sensitive")).isEqualTo(true);
        assertThat(events.get(0).get("dedupe_key")).isEqualTo("invite:" + id + ":1");
        assertThat(events.get(1).get("sensitive")).isEqualTo(false);
        assertThat(events.get(1).get("action")).isEqualTo("invite.created");
        Map<String, String> claims = SignedActionToken.verify(
                "invite",
                tokenOf((String) events.get(0).get("accept_url")),
                signing.signingKey(),
                java.time.Clock.systemUTC());
        assertThat(claims)
                .containsEntry("jti", id.toString())
                .containsEntry("orgId", org.orgId())
                .containsEntry("email", "jane@example.com")
                .containsEntry("role", "admin");
        Long storedExpiry = jdbc.queryForObject(
                "SELECT extract(epoch FROM expires_at)::bigint FROM org_team.invites WHERE id = ?", Long.class, id);
        assertThat(claims.get("exp")).isEqualTo(String.valueOf(storedExpiry));
    }

    @Test
    void noLogLineCarriesTheTokenOrTheInviteesEmail(CapturedOutput output) throws Exception {
        TestOrg org = newTeamOrg();
        createdId(org, org.owner(), "leaky.address@example.com", "VIEWER");
        String token = tokenOf(acceptUrls(org.orgId()).get(0));

        assertThat(output.getAll()).doesNotContain(token).doesNotContain("leaky.address@example.com");
    }

    @Test
    void aDuplicateInviteIsAConflict() throws Exception {
        TestOrg org = newTeamOrg();
        invite(org.orgId(), org.owner(), "jane@example.com", "VIEWER").andExpect(status().isCreated());

        invite(org.orgId(), org.owner(), "JANE@example.com", "DEVELOPER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVITE_ALREADY_PENDING"));
    }

    @Test
    void twentyConcurrentCreatesForOneEmailYieldExactlyOneInvite() throws Exception {
        TestOrg org = newTeamOrg();
        int callers = 20;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return invite(org.orgId(), org.owner(), "race@example.com", "VIEWER")
                        .andReturn()
                        .getResponse()
                        .getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s != 201).allMatch(s -> s == 409);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.invites WHERE org_id = ?", Integer.class, org.orgId()))
                .isEqualTo(1);
        assertThat(acceptUrls(org.orgId())).hasSize(1);
    }

    @Test
    void anActiveMembersEmailAndARemovedMembersEmailAreDistinctConflicts() throws Exception {
        TestOrg org = newTeamOrg();
        String active = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");
        String removed = fixtures.newMember(org.orgId(), "DEVELOPER", "REMOVED");

        invite(org.orgId(), org.owner(), active + "@example.com", "VIEWER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ALREADY_A_MEMBER"));
        invite(org.orgId(), org.owner(), removed + "@example.com", "VIEWER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("MEMBER_PREVIOUSLY_REMOVED"));
    }

    @Test
    void thePendingQuotaIsEnforced() throws Exception {
        TestOrg org = newTeamOrg();
        for (int i = 0; i < 50; i++) {
            insertPendingInvite(org.orgId(), "seed" + i + "@example.com", org.owner());
        }

        invite(org.orgId(), org.owner(), "one-too-many@example.com", "VIEWER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("QUOTA_EXCEEDED"));
    }

    @Test
    void theRoleMatrixHoldsAtTheEdge() throws Exception {
        TestOrg org = newTeamOrg();
        String admin = fixtures.newMember(org.orgId(), "ADMIN", "ACTIVE");
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");

        invite(org.orgId(), admin, "a@example.com", "ADMIN")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));
        invite(org.orgId(), admin, "b@example.com", "DEVELOPER").andExpect(status().isCreated());
        invite(org.orgId(), org.owner(), "c@example.com", "OWNER").andExpect(status().isConflict());
        invite(org.orgId(), dev, "d@example.com", "VIEWER").andExpect(status().isForbidden());
    }

    @Test
    void anotherOrgsCallerCannotSeeOrTouchTheInvites() throws Exception {
        TestOrg org = newTeamOrg();
        TestOrg other = newTeamOrg();
        UUID id = createdId(org, org.owner(), "jane@example.com", "VIEWER");

        perform(get(BASE, org.orgId()), other.orgId(), other.owner()).andExpect(status().isNotFound());
        perform(delete(BASE + "/{id}", other.orgId(), id), other.orgId(), other.owner())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("INVITE_NOT_FOUND"));
        assertThat(statusOf(id)).isEqualTo("PENDING");
    }

    @Test
    void resendWithinTheCooldownIsRateLimited() throws Exception {
        TestOrg org = newTeamOrg();
        UUID id = createdId(org, org.owner(), "jane@example.com", "VIEWER");

        perform(post(BASE + "/{id}/resend", org.orgId(), id), org.orgId(), org.owner())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.meta.retryAfter").exists());
        assertThat(acceptUrls(org.orgId())).hasSize(1);
    }

    @Test
    void resendAfterTheCooldownSendsANewLinkForTheSameInvite() throws Exception {
        TestOrg org = newTeamOrg();
        UUID id = createdId(org, org.owner(), "jane@example.com", "VIEWER");
        Long firstExpiry = jdbc.queryForObject(
                "SELECT extract(epoch FROM expires_at)::bigint FROM org_team.invites WHERE id = ?", Long.class, id);
        jdbc.update("UPDATE org_team.invites SET expires_at = expires_at - interval '1 hour' WHERE id = ?", id);
        backdateLastSend(id);

        perform(post(BASE + "/{id}/resend", org.orgId(), id), org.orgId(), org.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sendCount").value(2))
                .andExpect(jsonPath("$.data.acceptUrl").doesNotExist());

        List<Map<String, Object>> notifications = outbox(org.orgId()).stream()
                .filter(e -> "notification.requested".equals(e.get("event_type")))
                .toList();
        assertThat(notifications)
                .extracting(e -> e.get("dedupe_key"))
                .containsExactly("invite:" + id + ":1", "invite:" + id + ":2");
        assertThat(notifications).allMatch(e -> Boolean.TRUE.equals(e.get("sensitive")));
        Map<String, String> resent = SignedActionToken.verify(
                "invite",
                tokenOf((String) notifications.get(1).get("accept_url")),
                signing.signingKey(),
                java.time.Clock.systemUTC());
        assertThat(resent).containsEntry("jti", id.toString());
        assertThat(Long.parseLong(resent.get("exp"))).isGreaterThan(firstExpiry - 3600);
    }

    @Test
    void resendStopsAtTheSendCap() throws Exception {
        TestOrg org = newTeamOrg();
        UUID id = createdId(org, org.owner(), "jane@example.com", "VIEWER");
        for (int send = 2; send <= 4; send++) {
            backdateLastSend(id);
            perform(post(BASE + "/{id}/resend", org.orgId(), id), org.orgId(), org.owner())
                    .andExpect(status().isOk());
        }
        backdateLastSend(id);

        perform(post(BASE + "/{id}/resend", org.orgId(), id), org.orgId(), org.owner())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("QUOTA_EXCEEDED"));
    }

    @Test
    void anAdminCannotResendOrRevokeAnAdminInvite() throws Exception {
        TestOrg org = newTeamOrg();
        String admin = fixtures.newMember(org.orgId(), "ADMIN", "ACTIVE");
        UUID id = createdId(org, org.owner(), "jane@example.com", "ADMIN");
        backdateLastSend(id);

        perform(post(BASE + "/{id}/resend", org.orgId(), id), org.orgId(), admin)
                .andExpect(status().isForbidden());
        perform(delete(BASE + "/{id}", org.orgId(), id), org.orgId(), admin).andExpect(status().isForbidden());
        assertThat(statusOf(id)).isEqualTo("PENDING");
    }

    @Test
    void revokeMarksTheInviteRevokedAndASecondRevokeIsNotFound() throws Exception {
        TestOrg org = newTeamOrg();
        UUID id = createdId(org, org.owner(), "jane@example.com", "VIEWER");

        perform(delete(BASE + "/{id}", org.orgId(), id), org.orgId(), org.owner())
                .andExpect(status().isNoContent());

        assertThat(statusOf(id)).isEqualTo("REVOKED");
        assertThat(outbox(org.orgId())).extracting(e -> e.get("action")).contains("invite.revoked");
        perform(delete(BASE + "/{id}", org.orgId(), id), org.orgId(), org.owner())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("INVITE_NOT_FOUND"));
        perform(post(BASE + "/{id}/resend", org.orgId(), id), org.orgId(), org.owner())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVITE_NOT_PENDING"));
        invite(org.orgId(), org.owner(), "jane@example.com", "VIEWER").andExpect(status().isCreated());
    }

    @Test
    void listIsAdminOnlyFilterableAndNewestFirst() throws Exception {
        TestOrg org = newTeamOrg();
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");
        UUID first = createdId(org, org.owner(), "first@example.com", "VIEWER");
        UUID second = createdId(org, org.owner(), "second@example.com", "VIEWER");
        perform(delete(BASE + "/{id}", org.orgId(), first), org.orgId(), org.owner());

        perform(get(BASE, org.orgId()), org.orgId(), dev).andExpect(status().isForbidden());
        perform(get(BASE, org.orgId()), org.orgId(), org.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].id").value(second.toString()))
                .andExpect(jsonPath("$.data.content[0].token").doesNotExist());
        perform(get(BASE, org.orgId()).param("status", "REVOKED"), org.orgId(), org.owner())
                .andExpect(jsonPath("$.data.content[*].id").value(hasItem(first.toString())))
                .andExpect(jsonPath("$.data.content[*].status", everyItem(is("REVOKED"))));
        perform(get(BASE, org.orgId()).param("sort", "email"), org.orgId(), org.owner())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_SORT"));
    }

    @Test
    void anUnsweptExpiredInviteReadsAsExpiredAndNoLongerBlocksANewOne() throws Exception {
        TestOrg org = newTeamOrg();
        UUID stale = createdId(org, org.owner(), "jane@example.com", "VIEWER");
        jdbc.update("UPDATE org_team.invites SET expires_at = now() - interval '1 minute' WHERE id = ?", stale);

        perform(get(BASE, org.orgId()), org.orgId(), org.owner())
                .andExpect(jsonPath("$.data.content[0].status").value("EXPIRED"));
        perform(get(BASE, org.orgId()).param("status", "EXPIRED"), org.orgId(), org.owner())
                .andExpect(jsonPath("$.data.totalElements").value(1));
        perform(get(BASE, org.orgId()).param("status", "PENDING"), org.orgId(), org.owner())
                .andExpect(jsonPath("$.data.totalElements").value(0));
        assertThat(statusOf(stale)).isEqualTo("PENDING");

        UUID fresh = createdId(org, org.owner(), "jane@example.com", "VIEWER");

        assertThat(statusOf(stale)).isEqualTo("EXPIRED");
        assertThat(statusOf(fresh)).isEqualTo("PENDING");
    }
}
