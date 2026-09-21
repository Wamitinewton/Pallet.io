package io.pallet.orgteam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.annotations.RepositoryTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@RepositoryTest
class SchemaConstraintsIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aSecondActiveOwnerIsRejected() {
        org("org-a");
        member("org-a", "u1", "one@example.com", "OWNER", "ACTIVE");

        assertRejected(() -> member("org-a", "u2", "two@example.com", "OWNER", "ACTIVE"));
    }

    @Test
    void aRemovedOwnerDoesNotBlockANewActiveOwner() {
        org("org-a");
        member("org-a", "u1", "one@example.com", "OWNER", "REMOVED");

        assertThatCode(() -> member("org-a", "u2", "two@example.com", "OWNER", "ACTIVE"))
                .doesNotThrowAnyException();
    }

    @Test
    void activeMembershipsShareNoCaseInsensitiveEmailWithinAnOrg() {
        org("org-a");
        member("org-a", "u1", "Dev@Example.com", "DEVELOPER", "ACTIVE");

        assertRejected(() -> member("org-a", "u2", "dev@example.com", "VIEWER", "ACTIVE"));
    }

    @Test
    void theSameEmailMayBelongToDifferentOrgs() {
        org("org-a");
        org("org-b");
        member("org-a", "u1", "dev@example.com", "DEVELOPER", "ACTIVE");

        assertThatCode(() -> member("org-b", "u2", "dev@example.com", "DEVELOPER", "ACTIVE"))
                .doesNotThrowAnyException();
    }

    @Test
    void aSecondPendingInviteForTheSameEmailIsRejected() {
        org("org-a");
        invite("org-a", "Dev@Example.com", "PENDING");

        assertRejected(() -> invite("org-a", "dev@example.com", "PENDING"));
    }

    @Test
    void aRevokedInviteCoexistsWithANewPendingOne() {
        org("org-a");
        invite("org-a", "dev@example.com", "REVOKED");

        assertThatCode(() -> invite("org-a", "dev@example.com", "PENDING")).doesNotThrowAnyException();
    }

    @Test
    void aTeamMemberMustBeAMemberOfTheSameOrg() {
        org("org-a");
        org("org-b");
        member("org-b", "u1", "one@example.com", "DEVELOPER", "ACTIVE");
        UUID team = team("org-a", "platform");

        assertRejected(() -> teamMember(team, "org-a", "u1"));
    }

    @Test
    void aTeamMemberMustReferenceATeamInItsOwnOrg() {
        org("org-a");
        org("org-b");
        member("org-b", "u1", "one@example.com", "DEVELOPER", "ACTIVE");
        UUID team = team("org-a", "platform");

        assertRejected(() -> teamMember(team, "org-b", "u1"));
    }

    @Test
    void anAppCannotPointAtAnotherOrgsTeam() {
        org("org-a");
        org("org-b");
        UUID team = team("org-a", "platform");

        assertRejected(() -> app("org-b", team, "api", "AWS", "us-east-1"));
    }

    @Test
    void anAppsProviderAndRegionAreImmutable() {
        org("org-a");
        UUID app = app("org-a", null, "api", "AWS", "us-east-1");

        assertRejected(() -> jdbc.update("update org_team.apps set region = 'eu-west-1' where id = ?", app));
    }

    @Test
    void anAppsProviderIsImmutable() {
        org("org-a");
        UUID app = app("org-a", null, "api", "AWS", "us-east-1");

        assertRejected(() -> jdbc.update("update org_team.apps set cloud_provider = 'GCP' where id = ?", app));
    }

    @Test
    void otherAppColumnsRemainUpdatable() {
        org("org-a");
        UUID app = app("org-a", null, "api", "AWS", "us-east-1");

        assertThat(jdbc.update("update org_team.apps set name = 'renamed' where id = ?", app))
                .isEqualTo(1);
    }

    @Test
    void deletingATeamDetachesItsAppsButKeepsTheirOrg() {
        org("org-a");
        UUID team = team("org-a", "platform");
        UUID app = app("org-a", team, "api", "AWS", "us-east-1");

        jdbc.update("delete from org_team.teams where id = ?", team);

        assertThat(jdbc.queryForObject("select team_id from org_team.apps where id = ?", UUID.class, app))
                .isNull();
        assertThat(jdbc.queryForObject("select org_id from org_team.apps where id = ?", String.class, app))
                .isEqualTo("org-a");
    }

    @Test
    void activeAppSlugsAreUniquePerOrg() {
        org("org-a");
        app("org-a", null, "api", "AWS", "us-east-1");

        assertRejected(() -> app("org-a", null, "api", "GCP", "us-east1"));
    }

    @Test
    void aDeletedAppsSlugCanBeReused() {
        org("org-a");
        UUID first = app("org-a", null, "api", "AWS", "us-east-1");
        jdbc.update("update org_team.apps set status = 'DELETED', deleted_at = now() where id = ?", first);

        assertThatCode(() -> app("org-a", null, "api", "GCP", "us-east1")).doesNotThrowAnyException();
    }

    @Test
    void aDuplicateOutboxEventIdIsRejected() {
        UUID eventId = UUID.randomUUID();
        outbox(eventId);

        assertRejected(() -> outbox(eventId));
    }

    @Test
    void aDuplicateProcessedEventForOneConsumerIsRejected() {
        UUID eventId = UUID.randomUUID();
        processed(eventId, "org-provisioned");

        assertRejected(() -> processed(eventId, "org-provisioned"));
    }

    @Test
    void oneEventMayBeProcessedByDifferentConsumers() {
        UUID eventId = UUID.randomUUID();
        processed(eventId, "org-provisioned");

        assertThatCode(() -> processed(eventId, "invite-accepted")).doesNotThrowAnyException();
    }

    @Test
    void aDeletedOrgsSlugIsNeverReissued() {
        jdbc.update("insert into org_team.organizations (org_id, name, slug, owner_user_id, status)"
                + " values ('org-a', 'A', 'acme', 'u1', 'DELETED')");

        assertRejected(
                () -> jdbc.update("insert into org_team.organizations (org_id, name, slug, owner_user_id, status)"
                        + " values ('org-b', 'B', 'acme', 'u2', 'ACTIVE')"));
    }

    private void assertRejected(Runnable statement) {
        assertThatThrownBy(statement::run).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void org(String orgId) {
        jdbc.update(
                "insert into org_team.organizations (org_id, name, slug, owner_user_id, status)"
                        + " values (?, ?, ?, 'owner', 'ACTIVE')",
                orgId,
                orgId,
                orgId);
    }

    private void member(String orgId, String userId, String email, String role, String status) {
        jdbc.update(
                "insert into org_team.memberships (org_id, user_id, email, display_name, role, status)"
                        + " values (?, ?, ?, ?, ?, ?)",
                orgId,
                userId,
                email,
                userId,
                role,
                status);
    }

    private void invite(String orgId, String email, String status) {
        jdbc.update(
                "insert into org_team.invites (id, org_id, email, role, invited_by_user_id, status, expires_at)"
                        + " values (?, ?, ?, 'DEVELOPER', 'owner', ?, now() + interval '1 day')",
                UUID.randomUUID(),
                orgId,
                email,
                status);
    }

    private UUID team(String orgId, String slug) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into org_team.teams (id, org_id, name, slug) values (?, ?, ?, ?)", id, orgId, slug, slug);
        return id;
    }

    private void teamMember(UUID teamId, String orgId, String userId) {
        jdbc.update(
                "insert into org_team.team_members (team_id, user_id, org_id, added_by) values (?, ?, ?, 'owner')",
                teamId,
                userId,
                orgId);
    }

    private UUID app(String orgId, UUID teamId, String slug, String provider, String region) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into org_team.apps (id, org_id, team_id, name, slug, cloud_provider, region, status)"
                        + " values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')",
                id,
                orgId,
                teamId,
                slug,
                slug,
                provider,
                region);
        return id;
    }

    private void outbox(UUID eventId) {
        jdbc.update(
                "insert into org_team.outbox_events (event_id, org_id, event_type) values (?, 'org-a', 'org.member.added')",
                eventId);
    }

    private void processed(UUID eventId, String consumer) {
        jdbc.update("insert into org_team.processed_events (event_id, consumer) values (?, ?)", eventId, consumer);
    }
}
