package io.pallet.orgteam.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.pallet.common.events.OrgInviteAccepted;
import io.pallet.common.events.OrgInviteRejected;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.invite.InviteAcceptanceService;
import io.pallet.orgteam.outbox.OutboxMetrics;
import io.pallet.orgteam.security.SignedTokenTestConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, SignedTokenTestConfiguration.class})
class MetricsIntegrationTest extends ObservabilityIntegrationSupport {

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private InviteAcceptanceService acceptance;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private OutboxMetrics outboxMetrics;

    private double count(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private double gauge(String name) {
        Gauge gauge = registry.find(name).gauge();
        assertThat(gauge).as(name).isNotNull();
        return gauge.value();
    }

    private void accept(String orgId, UUID inviteId, String userId, String email) {
        OrgInviteAccepted event = new OrgInviteAccepted(
                UUID.randomUUID(),
                OrgInviteAccepted.TYPE,
                orgId,
                Instant.now(),
                inviteId.toString(),
                userId,
                email,
                "Jane Doe",
                "developer");
        transaction.executeWithoutResult(status -> acceptance.handle(event));
    }

    @Test
    void eachDomainActionMovesItsCounterByExactlyOne() throws Exception {
        TestOrg org = newTeamOrg();
        String orgId = org.orgId();
        String developer = fixtures.newMember(orgId, "DEVELOPER", "ACTIVE");
        String viewer = fixtures.newMember(orgId, "VIEWER", "ACTIVE");

        double created = count(MetricsCatalog.INVITES_CREATED);
        perform(
                        post(API + "/orgs/{orgId}/invites", orgId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"jane@example.com\",\"role\":\"DEVELOPER\"}"),
                        orgId,
                        org.owner())
                .andExpect(status().isCreated());
        assertThat(count(MetricsCatalog.INVITES_CREATED)).isEqualTo(created + 1);

        UUID inviteId = jdbc.queryForObject("SELECT id FROM org_team.invites WHERE org_id = ?", UUID.class, orgId);
        double accepted = count(MetricsCatalog.INVITES_ACCEPTED);
        double added = count(MetricsCatalog.MEMBERS_ADDED);
        double processed = count(
                MetricsCatalog.EVENTS_PROCESSED,
                MetricsCatalog.TAG_LISTENER,
                MetricsCatalog.LISTENER_ORG_INVITE_ACCEPTED);
        accept(orgId, inviteId, "user-" + UUID.randomUUID(), "jane@example.com");
        assertThat(count(MetricsCatalog.INVITES_ACCEPTED)).isEqualTo(accepted + 1);
        assertThat(count(MetricsCatalog.MEMBERS_ADDED)).isEqualTo(added + 1);
        assertThat(count(
                        MetricsCatalog.EVENTS_PROCESSED,
                        MetricsCatalog.TAG_LISTENER,
                        MetricsCatalog.LISTENER_ORG_INVITE_ACCEPTED))
                .isEqualTo(processed + 1);

        double rejected = count(
                MetricsCatalog.INVITES_REJECTED, MetricsCatalog.TAG_REASON, OrgInviteRejected.REASON_UNKNOWN_INVITE);
        accept(orgId, UUID.randomUUID(), "user-" + UUID.randomUUID(), "ghost@example.com");
        assertThat(count(
                        MetricsCatalog.INVITES_REJECTED,
                        MetricsCatalog.TAG_REASON,
                        OrgInviteRejected.REASON_UNKNOWN_INVITE))
                .isEqualTo(rejected + 1);

        double roleChanged = count(MetricsCatalog.MEMBERS_ROLE_CHANGED);
        perform(
                        patch(API + "/orgs/{orgId}/members/{userId}", orgId, developer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"ADMIN\"}"),
                        orgId,
                        org.owner())
                .andExpect(status().isOk());
        assertThat(count(MetricsCatalog.MEMBERS_ROLE_CHANGED)).isEqualTo(roleChanged + 1);

        double removed = count(MetricsCatalog.MEMBERS_REMOVED);
        perform(delete(API + "/orgs/{orgId}/members/{userId}", orgId, viewer), orgId, org.owner())
                .andExpect(status().isNoContent());
        assertThat(count(MetricsCatalog.MEMBERS_REMOVED)).isEqualTo(removed + 1);

        double apps = count(MetricsCatalog.APPS_CREATED);
        perform(
                        post(API + "/orgs/{orgId}/apps", orgId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Web\",\"cloudProvider\":\"AWS\",\"region\":\"us-east-1\"}"),
                        orgId,
                        org.owner())
                .andExpect(status().isCreated());
        assertThat(count(MetricsCatalog.APPS_CREATED)).isEqualTo(apps + 1);

        double deleted = count(MetricsCatalog.ORGS_DELETED);
        perform(delete(API + "/orgs/{orgId}", orgId).header("X-Confirm-Slug", orgId), orgId, org.owner())
                .andExpect(status().isNoContent());
        assertThat(count(MetricsCatalog.ORGS_DELETED)).isEqualTo(deleted + 1);
    }

    @Test
    void aRolledBackActionCountsNothing() throws Exception {
        TestOrg org = newTeamOrg();
        double created = count(MetricsCatalog.INVITES_CREATED);

        perform(
                        post(API + "/orgs/{orgId}/invites", org.orgId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"jane@example.com\",\"role\":\"OWNER\"}"),
                        org.orgId(),
                        org.owner())
                .andExpect(status().is4xxClientError());

        assertThat(count(MetricsCatalog.INVITES_CREATED)).isEqualTo(created);
    }

    @Test
    void authorizationDenialsAreCountedByReason() throws Exception {
        TestOrg org = newTeamOrg();
        TestOrg other = newTeamOrg();
        String viewer = fixtures.newMember(org.orgId(), "VIEWER", "ACTIVE");
        double mismatch =
                count(MetricsCatalog.AUTHZ_DENIED, MetricsCatalog.TAG_REASON, MetricsCatalog.DENIED_ORG_MISMATCH);
        double insufficient =
                count(MetricsCatalog.AUTHZ_DENIED, MetricsCatalog.TAG_REASON, MetricsCatalog.DENIED_INSUFFICIENT_ROLE);

        perform(get(API + "/orgs/{orgId}", other.orgId()), org.orgId(), org.owner())
                .andExpect(status().isNotFound());
        perform(
                        patch(API + "/orgs/{orgId}", org.orgId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Renamed\"}"),
                        org.orgId(),
                        viewer)
                .andExpect(status().isForbidden());

        assertThat(count(MetricsCatalog.AUTHZ_DENIED, MetricsCatalog.TAG_REASON, MetricsCatalog.DENIED_ORG_MISMATCH))
                .isEqualTo(mismatch + 1);
        assertThat(count(
                        MetricsCatalog.AUTHZ_DENIED,
                        MetricsCatalog.TAG_REASON,
                        MetricsCatalog.DENIED_INSUFFICIENT_ROLE))
                .isEqualTo(insufficient + 1);
    }

    @Test
    void noOrgTeamMeterCarriesAnIdentifyingLabel() throws Exception {
        TestOrg org = newTeamOrg();
        perform(get(API + "/orgs/{orgId}/members", org.orgId()), org.orgId(), org.owner())
                .andExpect(status().isOk());

        for (Meter meter : registry.getMeters()) {
            if (!meter.getId().getName().startsWith("orgteam.")) {
                continue;
            }
            assertThat(meter.getId().getTags())
                    .extracting(Tag::getKey)
                    .as(meter.getId().getName())
                    .doesNotContainAnyElementsOf(MetricsCatalog.FORBIDDEN_TAG_KEYS);
        }
    }

    @Test
    void theOutboxGaugesReflectSeededPendingAndParkedRows() {
        TestOrg org = newTeamOrg();
        outboxMetrics.refresh();
        double pending = gauge(MetricsCatalog.OUTBOX_PENDING);
        double parked = gauge(MetricsCatalog.OUTBOX_PARKED);

        for (int i = 0; i < 2; i++) {
            insertOutboxRow(org.orgId(), "PENDING", "now() - interval '90 seconds'");
        }
        insertOutboxRow(org.orgId(), "PARKED", "now()");
        outboxMetrics.refresh();

        assertThat(gauge(MetricsCatalog.OUTBOX_PENDING)).isEqualTo(pending + 2);
        assertThat(gauge(MetricsCatalog.OUTBOX_PARKED)).isEqualTo(parked + 1);
        assertThat(gauge(MetricsCatalog.OUTBOX_OLDEST_PENDING_AGE)).isGreaterThanOrEqualTo(90);
    }

    @Test
    void everyMeterTheArchitectureNamesIsRegistered() {
        for (String name : java.util.List.of(
                MetricsCatalog.OUTBOX_PENDING,
                MetricsCatalog.OUTBOX_OLDEST_PENDING_AGE,
                MetricsCatalog.OUTBOX_PARKED,
                MetricsCatalog.OUTBOX_RELAY_ACTIVE,
                MetricsCatalog.INVITES_CREATED,
                MetricsCatalog.INVITES_RESENT,
                MetricsCatalog.INVITES_REVOKED,
                MetricsCatalog.INVITES_ACCEPTED,
                MetricsCatalog.INVITES_EXPIRED,
                MetricsCatalog.MEMBERS_ADDED,
                MetricsCatalog.MEMBERS_REMOVED,
                MetricsCatalog.MEMBERS_ROLE_CHANGED,
                MetricsCatalog.APPS_CREATED,
                MetricsCatalog.ORGS_DELETED,
                MetricsCatalog.AUTHZ_TOKEN_ROLE_DRIFT,
                MetricsCatalog.PROFILE_NOT_YET_PROJECTED,
                MetricsCatalog.EVENTS_PROCESSED,
                MetricsCatalog.EVENTS_FAILED,
                MetricsCatalog.INBOX_DUPLICATES)) {
            assertThat(registry.find(name).meter()).as(name).isNotNull();
        }
    }

    private void insertOutboxRow(String orgId, String status, String createdAtSql) {
        jdbc.update(
                "INSERT INTO org_team.outbox_events (event_id, org_id, event_type, payload, status, created_at) "
                        + "VALUES (?, ?, 'org.member.added', '{}'::jsonb, ?, " + createdAtSql + ")",
                UUID.randomUUID(),
                orgId,
                status);
    }
}
