package io.pallet.buildqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.annotations.RepositoryTest;
import io.pallet.common.test.assertions.OutboxSchemaAssertions;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The constraints the design relies on, checked against the migrated schema. A rejected statement aborts the test's
 * transaction, so each rejection is a test's last statement.
 */
@RepositoryTest
class SchemaConstraintsIntegrationTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-10-01T12:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void twoWaitingBuildsForOneAppAndBranchAreRejected() {
        String appId = app("org-a");
        build("org-a", appId, "main", "QUEUED").insert();

        assertRejected(() -> build("org-a", appId, "main", "QUEUED").insert(), "ux_builds_app_branch_queued");
    }

    @Test
    void aWaitingBuildBesideASupersededOneIsAccepted() {
        String appId = app("org-a");
        build("org-a", appId, "main", "SUPERSEDED").insert();

        assertThatCode(() -> build("org-a", appId, "main", "QUEUED").insert()).doesNotThrowAnyException();
    }

    @Test
    void oneAppMayHaveAWaitingBuildOnEachBranch() {
        String appId = app("org-a");
        build("org-a", appId, "main", "QUEUED").insert();

        assertThatCode(() -> build("org-a", appId, "release", "QUEUED").insert())
                .doesNotThrowAnyException();
    }

    @Test
    void twoBuildsOfOneAppInFlightAreRejected() {
        String appId = app("org-a");
        build("org-a", appId, "main", "DISPATCHED").insert();

        assertRejected(() -> build("org-a", appId, "release", "RUNNING").insert(), "ux_builds_app_in_flight");
    }

    @Test
    void anExpiredBuildDoesNotHoldItsAppsSlot() {
        String appId = app("org-a");
        build("org-a", appId, "main", "EXPIRED").insert();

        assertThatCode(() -> build("org-a", appId, "main", "RUNNING").insert()).doesNotThrowAnyException();
    }

    @Test
    void twoAppsMayEachHaveABuildInFlight() {
        String first = app("org-a");
        String second = app("org-a");
        build("org-a", first, "main", "DISPATCHED").insert();

        assertThatCode(() -> build("org-a", second, "main", "RUNNING").insert()).doesNotThrowAnyException();
    }

    @Test
    void aBuildForAnUnknownAppIsRejected() {
        assertRejected(() -> build("org-a", "app-unknown", "main", "QUEUED").insert(), "fk_builds_app");
    }

    @Test
    void aBuildFilingAnAppUnderAnotherOrgIsRejected() {
        String appId = app("org-a");

        assertRejected(() -> build("org-b", appId, "main", "QUEUED").insert(), "fk_builds_app");
    }

    @Test
    void aDispatchedBuildWithoutAStartDeadlineIsRejected() {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, "main", "DISPATCHED")
                        .set("start_deadline", null)
                        .insert(),
                "ck_builds_dispatched_deadline");
    }

    @Test
    void aDispatchedBuildThatWasNeverAttemptedIsRejected() {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, "main", "DISPATCHED")
                        .set("attempt", 0)
                        .insert(),
                "ck_builds_dispatched_deadline");
    }

    @Test
    void aRunningBuildWithoutALeaseIsRejected() {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, "main", "RUNNING")
                        .set("lease_expires_at", null)
                        .insert(),
                "ck_builds_running_lease");
    }

    @Test
    void aRunningBuildWithoutAStartTimeIsRejected() {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, "main", "RUNNING")
                        .set("started_at", null)
                        .insert(),
                "ck_builds_running_lease");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "0123456789ABCDEF0123456789ABCDEF01234567",
                "0123456789abcdef0123456789abcdef0123456",
                "0123456789abcdef0123456789abcdef012345678",
                "z123456789abcdef0123456789abcdef01234567"
            })
    void aCommitShaThatIsNotFortyLowercaseHexIsRejected(String commitSha) {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, "main", "QUEUED")
                        .set("commit_sha", commitSha)
                        .insert(),
                "ck_builds_commit_sha");
    }

    @Test
    void aSupersededBuildWithAnotherReasonIsRejected() {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, "main", "SUPERSEDED")
                        .set("status_reason", "USER_CANCELLED")
                        .insert(),
                "ck_builds_superseded_reason");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "FAILED", "SUPERSEDED", "CANCELLED", "REJECTED", "EXPIRED"})
    void aBuildThatLeftTheQueueWithoutAFinishTimeIsRejected(String status) {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, "main", status)
                        .set("finished_at", null)
                        .insert(),
                "ck_builds_finished_at");
    }

    @Test
    void anUnknownStatusIsRejected() {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, "main", "FAILED")
                        .set("status", "PAUSED")
                        .insert(),
                "ck_builds_status");
    }

    @Test
    void anUnknownTriggerIsRejected() {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, "main", "QUEUED")
                        .set("trigger", "CRON")
                        .insert(),
                "ck_builds_trigger");
    }

    @Test
    void anUnknownPriorityClassIsRejected() {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, "main", "QUEUED")
                        .set("priority_class", 2)
                        .insert(),
                "ck_builds_priority_class");
    }

    @Test
    void aSecondBuildFromOnePushIsRejected() {
        String appId = app("org-a");
        UUID pushEventId = UUID.randomUUID();
        build("org-a", appId, "main", "SUPERSEDED")
                .set("push_event_id", pushEventId)
                .insert();

        assertRejected(
                () -> build("org-a", appId, "main", "QUEUED")
                        .set("push_event_id", pushEventId)
                        .insert(),
                "ux_builds_push_event_id");
    }

    @Test
    void supersedingAWaitingBuildThenInsertingItsReplacementCommits() {
        String appId = app("org-a");
        UUID waiting = build("org-a", appId, "main", "QUEUED").insert();
        UUID replacement = UUID.randomUUID();

        supersede(waiting, replacement);
        build("org-a", appId, "main", "QUEUED").set("build_id", replacement).insert();

        assertThatCode(this::checkDeferredConstraints).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject(
                        "select superseded_by from build_queue.builds where build_id = ?", UUID.class, waiting))
                .isEqualTo(replacement);
    }

    @Test
    void supersedingByABuildThatNeverArrivesFailsAtCommit() {
        String appId = app("org-a");
        UUID waiting = build("org-a", appId, "main", "QUEUED").insert();
        supersede(waiting, UUID.randomUUID());

        assertRejected(this::checkDeferredConstraints, "fk_builds_superseded_by");
    }

    @Test
    void deletingTheSupersedingBuildClearsThePointerToIt() {
        String appId = app("org-a");
        UUID waiting = build("org-a", appId, "main", "QUEUED").insert();
        UUID replacement = UUID.randomUUID();
        supersede(waiting, replacement);
        build("org-a", appId, "main", "QUEUED").set("build_id", replacement).insert();
        checkDeferredConstraints();

        jdbc.update("delete from build_queue.builds where build_id = ?", replacement);

        assertThat(jdbc.queryForObject(
                        "select superseded_by from build_queue.builds where build_id = ?", UUID.class, waiting))
                .isNull();
    }

    @Test
    void theLeaseSweeperStateHoldsExactlyOneRow() {
        assertRejected(
                () -> jdbc.update("insert into build_queue.lease_sweeper_state (id) values (2)"),
                "ck_lease_sweeper_state_singleton");
    }

    @Test
    void anUnknownAppStatusIsRejected() {
        assertRejected(
                () -> jdbc.update(
                        "insert into build_queue.apps (app_id, org_id, status) values ('app-x', 'org-a', 'ARCHIVED')"),
                "ck_apps_status");
    }

    @Test
    void anUnknownOrgStatusIsRejected() {
        assertRejected(
                () -> jdbc.update("insert into build_queue.orgs (org_id, status) values ('org-a', 'SUSPENDED')"),
                "ck_orgs_status");
    }

    @Test
    void anUnknownMembershipStatusIsRejected() {
        assertRejected(
                () -> jdbc.update("insert into build_queue.org_memberships (org_id, user_id, role, status,"
                        + " source_version) values ('org-a', 'user-1', 'VIEWER', 'INVITED', 1)"),
                "ck_org_memberships_status");
    }

    @Test
    void everyIndexTheDesignListsExists() {
        List<String> indexes = jdbc.queryForList(
                "select indexname from pg_indexes where schemaname = 'build_queue' and tablename = 'builds'",
                String.class);

        assertThat(indexes)
                .contains(
                        "ux_builds_app_in_flight",
                        "ux_builds_app_branch_queued",
                        "ux_builds_push_event_id",
                        "ix_builds_queued",
                        "ix_builds_in_flight",
                        "ix_builds_start_deadline",
                        "ix_builds_lease",
                        "ix_builds_queue_age",
                        "ix_builds_app_history",
                        "ix_builds_finished",
                        "ix_builds_superseded_by");
    }

    @Test
    void theOutboxTablesMatchTheModulesReferenceSchema() {
        OutboxSchemaAssertions.assertMatchesReference(dataSource, "build_queue");
    }

    private String app(String orgId) {
        String appId = "app-" + UUID.randomUUID();
        jdbc.update(
                "insert into build_queue.apps (app_id, org_id, slug, status) values (?, ?, ?, 'ACTIVE')",
                appId,
                orgId,
                appId.substring(0, 12));
        return appId;
    }

    private void supersede(UUID buildId, UUID supersededBy) {
        jdbc.update(
                "update build_queue.builds set status = 'SUPERSEDED', status_reason = 'SUPERSEDED',"
                        + " superseded_by = ?, finished_at = now() where build_id = ? and status = 'QUEUED'",
                supersededBy,
                buildId);
    }

    /** What commit would check: the deferred foreign key, without ending the test's rolled-back transaction. */
    private void checkDeferredConstraints() {
        jdbc.execute("set constraints all immediate");
    }

    /** A row consistent with {@code status}, so a test only overrides the column it is about. */
    private BuildRow build(String orgId, String appId, String branch, String status) {
        BuildRow row = new BuildRow()
                .set("build_id", UUID.randomUUID())
                .set("org_id", orgId)
                .set("app_id", appId)
                .set("branch", branch)
                .set("commit_sha", SHA)
                .set("push_event_id", UUID.randomUUID())
                .set("trigger", "WEBHOOK")
                .set("priority_class", 1)
                .set("provider", "GITHUB")
                .set("installation_id", 1L)
                .set("repo_id", 10L)
                .set("repo", "acme/web")
                .set("status", status)
                .set("enqueued_at", AT);
        switch (status) {
            case "QUEUED" -> {}
            case "DISPATCHED" -> row.set("attempt", 1).set("dispatched_at", AT).set("start_deadline", AT);
            case "RUNNING" ->
                row.set("attempt", 1)
                        .set("dispatched_at", AT)
                        .set("start_deadline", AT)
                        .set("started_at", AT)
                        .set("lease_expires_at", AT);
            case "SUPERSEDED" -> row.set("status_reason", "SUPERSEDED").set("finished_at", AT);
            default -> row.set("finished_at", AT);
        }
        return row;
    }

    private final class BuildRow {

        private final Map<String, Object> columns = new LinkedHashMap<>();

        BuildRow set(String column, Object value) {
            columns.put(column, value);
            return this;
        }

        UUID insert() {
            List<Object> values = new ArrayList<>(columns.values());
            jdbc.update(
                    "insert into build_queue.builds (" + String.join(", ", columns.keySet()) + ") values ("
                            + String.join(", ", Collections.nCopies(columns.size(), "?")) + ")",
                    values.toArray());
            return (UUID) columns.get("build_id");
        }
    }

    private static void assertRejected(Runnable statement, String constraint) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }
}
