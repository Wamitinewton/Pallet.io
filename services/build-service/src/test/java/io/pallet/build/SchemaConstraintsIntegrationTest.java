package io.pallet.build;

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
    private static final String DIGEST = "sha256:" + "0123456789abcdef".repeat(4);
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-10-01T12:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void aSecondBuildWithTheSameIdIsRejected() {
        String appId = app("org-a");
        String buildId = build("org-a", appId, "ACCEPTED").insert();

        assertRejected(
                () -> build("org-a", appId, "ACCEPTED").set("build_id", buildId).insert(), "pk_builds");
    }

    @Test
    void aBuildForAnUnknownAppIsRejected() {
        assertRejected(() -> build("org-a", "app-unknown", "ACCEPTED").insert(), "fk_builds_app");
    }

    @Test
    void aBuildFilingAnAppUnderAnotherOrgIsRejected() {
        String appId = app("org-a");

        assertRejected(() -> build("org-b", appId, "ACCEPTED").insert(), "fk_builds_app");
    }

    @ParameterizedTest
    @ValueSource(strings = {"STARTING", "RUNNING", "SUCCEEDED"})
    void aStartedBuildWithoutAStartTimeIsRejected(String status) {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, status).set("started_at", null).insert(), "ck_builds_started");
    }

    @ParameterizedTest
    @ValueSource(strings = {"APP_DELETED", "ORG_DELETED"})
    void aBuildFailedByTeardownBeforeItStartedIsAllowed(String failureCode) {
        String appId = app("org-a");

        assertThatCode(() -> build("org-a", appId, "FAILED")
                        .set("failure_code", failureCode)
                        .set("started_at", null)
                        .insert())
                .doesNotThrowAnyException();
    }

    @Test
    void aBuildThatFailedItsBuildWithoutStartingIsRejected() {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, "FAILED")
                        .set("failure_code", "BUILD_FAILED")
                        .set("started_at", null)
                        .insert(),
                "ck_builds_started");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "DROPPED"})
    void aBuildThatHasNotStartedNeedsNoStartTime(String status) {
        String appId = app("org-a");

        assertThatCode(() -> build("org-a", appId, status).insert()).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"image_digest", "image_tag", "strategy"})
    void aSuccessWithoutItsImageOrStrategyIsRejected(String column) {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, "SUCCEEDED").set(column, null).insert(), "ck_builds_success");
    }

    @Test
    void aFailureWithoutACodeIsRejected() {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, "FAILED").set("failure_code", null).insert(), "ck_builds_failure");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "DROPPED", "STARTING", "RUNNING", "SUCCEEDED"})
    void aFailureCodeOnAnyOtherStatusIsRejected(String status) {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, status)
                        .set("failure_code", "TIMEOUT")
                        .insert(),
                "ck_builds_failure");
    }

    @Test
    void anUnknownFailureCodeIsRejected() {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, "FAILED")
                        .set("failure_code", "OOPS")
                        .insert(),
                "ck_builds_failure_code");
    }

    @Test
    void aRunningBuildWithoutItsJobIsRejected() {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, "RUNNING")
                        .set("job_created_at", null)
                        .insert(),
                "ck_builds_running");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "FAILED"})
    void aTerminalBuildWithoutAFinishTimeIsRejected(String status) {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, status).set("finished_at", null).insert(), "ck_builds_terminal");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "DROPPED", "STARTING", "RUNNING"})
    void aBuildStillInPlayWithAFinishTimeIsRejected(String status) {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, status).set("finished_at", AT).insert(), "ck_builds_terminal");
    }

    @Test
    void anUnknownStatusIsRejected() {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, "STARTING").set("status", "PAUSED").insert(), "ck_builds_status");
    }

    @Test
    void aZeroAttemptIsRejected() {
        String appId = app("org-a");

        assertRejected(() -> build("org-a", appId, "ACCEPTED").set("attempt", 0).insert(), "ck_builds_attempt");
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
                () -> build("org-a", appId, "ACCEPTED")
                        .set("commit_sha", commitSha)
                        .insert(),
                "ck_builds_commit_sha");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "sha256:0123456789abcdef",
                "sha256:0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
                "sha512:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            })
    void anImageDigestThatIsNotSha256AndSixtyFourHexIsRejected(String digest) {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, "SUCCEEDED")
                        .set("image_digest", digest)
                        .insert(),
                "ck_builds_image_digest");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/srv/app", "..", "../app", "apps/../secrets", "apps/..", "apps/../"})
    void aRootDirectoryThatIsAbsoluteOrEscapesTheRepositoryIsRejected(String rootDirectory) {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, "ACCEPTED")
                        .set("root_directory", rootDirectory)
                        .insert(),
                "ck_builds_root_directory");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/Dockerfile", "../Dockerfile", "docker/../../Dockerfile", ""})
    void aDockerfilePathThatIsAbsoluteEscapesOrIsEmptyIsRejected(String dockerfilePath) {
        String appId = app("org-a");

        assertRejected(
                () -> build("org-a", appId, "ACCEPTED")
                        .set("dockerfile_path", dockerfilePath)
                        .insert(),
                "ck_builds_dockerfile_path");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "apps/web", "apps/..x", "..x/web", "apps/web..", ".config"})
    void aPathWithDotsThatAreNotAParentSegmentIsAllowed(String path) {
        String appId = app("org-a");
        String dockerfilePath = path.isEmpty() ? "Dockerfile" : path + "/Dockerfile";

        assertThatCode(() -> build("org-a", appId, "ACCEPTED")
                        .set("root_directory", path)
                        .set("dockerfile_path", dockerfilePath)
                        .insert())
                .doesNotThrowAnyException();
    }

    @Test
    void deletingABuildDeletesItsStepsAndLogChunks() {
        String appId = app("org-a");
        String buildId = build("org-a", appId, "RUNNING").insert();
        for (String step : List.of("FETCH", "BUILD", "PUSH")) {
            jdbc.update(
                    "insert into build.build_steps (build_id, step, status) values (?, ?, 'PENDING')", buildId, step);
        }
        jdbc.update(
                "insert into build.build_log_chunks (build_id, seq, org_id, step, content)"
                        + " values (?, 0, 'org-a', 'FETCH', 'fetching')",
                buildId);

        jdbc.update("delete from build.builds where build_id = ?", buildId);

        assertThat(count("build.build_steps", buildId)).isZero();
        assertThat(count("build.build_log_chunks", buildId)).isZero();
    }

    @Test
    void anUnknownStepIsRejected() {
        String appId = app("org-a");
        String buildId = build("org-a", appId, "RUNNING").insert();

        assertRejected(
                () -> jdbc.update(
                        "insert into build.build_steps (build_id, step, status) values (?, 'DEPLOY', 'PENDING')",
                        buildId),
                "ck_build_steps_step");
    }

    @Test
    void aStepForAnUnknownBuildIsRejected() {
        assertRejected(
                () -> jdbc.update(
                        "insert into build.build_steps (build_id, step, status) values ('nope', 'FETCH', 'PENDING')"),
                "fk_build_steps_build");
    }

    @Test
    void aNegativeLogSequenceIsRejected() {
        String appId = app("org-a");
        String buildId = build("org-a", appId, "RUNNING").insert();

        assertRejected(
                () -> jdbc.update(
                        "insert into build.build_log_chunks (build_id, seq, org_id, step, content)"
                                + " values (?, -1, 'org-a', 'SYSTEM', 'x')",
                        buildId),
                "ck_build_log_chunks_seq");
    }

    @Test
    void theObserverStateHoldsExactlyOneRow() {
        assertThat(jdbc.queryForObject("select count(*) from build.observer_state", Integer.class))
                .isOne();

        assertRejected(() -> jdbc.update("insert into build.observer_state (id) values (true)"), "pk_observer_state");
    }

    @Test
    void theObserverStateRefusesAnyOtherKey() {
        assertRejected(
                () -> jdbc.update("insert into build.observer_state (id) values (false)"),
                "ck_observer_state_singleton");
    }

    @Test
    void buildSettingsForAnAppUnderAnotherOrgAreRejected() {
        String appId = app("org-a");

        assertRejected(
                () -> jdbc.update("insert into build.build_settings (org_id, app_id) values ('org-b', ?)", appId),
                "fk_build_settings_app");
    }

    @Test
    void buildSettingsDefaultToAutoAndGoWithTheirApp() {
        String appId = app("org-a");
        jdbc.update("insert into build.build_settings (org_id, app_id) values ('org-a', ?)", appId);

        assertThat(jdbc.queryForMap(
                        "select strategy, dockerfile_path, version from build.build_settings where app_id = ?", appId))
                .containsEntry("strategy", "AUTO")
                .containsEntry("dockerfile_path", "Dockerfile")
                .containsEntry("version", 0L);

        jdbc.update("delete from build.apps where app_id = ?", appId);

        assertThat(jdbc.queryForObject(
                        "select count(*) from build.build_settings where app_id = ?", Integer.class, appId))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/Dockerfile", "../Dockerfile", ""})
    void buildSettingsWithAnUnsafeDockerfilePathAreRejected(String dockerfilePath) {
        String appId = app("org-a");

        assertRejected(
                () -> jdbc.update(
                        "insert into build.build_settings (org_id, app_id, dockerfile_path) values ('org-a', ?, ?)",
                        appId,
                        dockerfilePath),
                "ck_build_settings_dockerfile_path");
    }

    @Test
    void buildSettingsWithAnUnknownStrategyAreRejected() {
        String appId = app("org-a");

        assertRejected(
                () -> jdbc.update(
                        "insert into build.build_settings (org_id, app_id, strategy) values ('org-a', ?, 'NIX')",
                        appId),
                "ck_build_settings_strategy");
    }

    @Test
    void anUnknownMembershipRoleIsRejected() {
        assertRejected(
                () -> jdbc.update("insert into build.org_memberships (org_id, user_id, role, status, source_version)"
                        + " values ('org-a', 'user-1', 'owner', 'ACTIVE', 1)"),
                "ck_org_memberships_role");
    }

    @Test
    void anUnknownMembershipStatusIsRejected() {
        assertRejected(
                () -> jdbc.update("insert into build.org_memberships (org_id, user_id, role, status, source_version)"
                        + " values ('org-a', 'user-1', 'VIEWER', 'INVITED', 1)"),
                "ck_org_memberships_status");
    }

    @Test
    void anUnknownAppStatusIsRejected() {
        assertRejected(
                () -> jdbc.update(
                        "insert into build.apps (app_id, org_id, status) values ('app-x', 'org-a', 'ARCHIVED')"),
                "ck_apps_status");
    }

    @Test
    void anUnknownOrgStatusIsRejected() {
        assertRejected(
                () -> jdbc.update("insert into build.orgs (org_id, status) values ('org-a', 'SUSPENDED')"),
                "ck_orgs_status");
    }

    @Test
    void everyIndexTheDesignListsExists() {
        List<String> indexes = jdbc.queryForList(
                "select indexname from pg_indexes where schemaname = 'build' and tablename = 'builds'", String.class);

        assertThat(indexes)
                .contains(
                        "pk_builds",
                        "ix_builds_accepted",
                        "ix_builds_in_flight",
                        "ix_builds_launchable",
                        "ix_builds_cleanup",
                        "ix_builds_app",
                        "ix_builds_retention");
    }

    @Test
    void theOutboxTablesMatchTheModulesReferenceSchema() {
        OutboxSchemaAssertions.assertMatchesReference(dataSource, "build");
    }

    private String app(String orgId) {
        String appId = "app-" + UUID.randomUUID();
        jdbc.update("insert into build.apps (app_id, org_id, status) values (?, ?, 'ACTIVE')", appId, orgId);
        return appId;
    }

    private int count(String table, String buildId) {
        return jdbc.queryForObject("select count(*) from " + table + " where build_id = ?", Integer.class, buildId);
    }

    /** A row consistent with {@code status}, so a test only overrides the column it is about. */
    private BuildRow build(String orgId, String appId, String status) {
        String buildId = UUID.randomUUID().toString();
        BuildRow row = new BuildRow()
                .set("build_id", buildId)
                .set("org_id", orgId)
                .set("app_id", appId)
                .set("branch", "main")
                .set("commit_sha", SHA)
                .set("push_event_id", UUID.randomUUID())
                .set("trigger", "WEBHOOK")
                .set("attempt", 1)
                .set("provider", "GITHUB")
                .set("installation_id", 1L)
                .set("repo_id", 10L)
                .set("repo", "acme/web")
                .set("root_directory", "")
                .set("start_by", AT)
                .set("max_duration_seconds", 1800L)
                .set("strategy_setting", "AUTO")
                .set("dockerfile_path", "Dockerfile")
                .set("status", status)
                .set("job_name", "build-" + buildId);
        switch (status) {
            case "ACCEPTED", "DROPPED" -> {}
            case "STARTING" -> row.set("started_at", AT);
            case "RUNNING" -> row.set("started_at", AT).set("job_created_at", AT);
            case "SUCCEEDED" ->
                row.set("started_at", AT)
                        .set("job_created_at", AT)
                        .set("finished_at", AT)
                        .set("strategy", "DOCKERFILE")
                        .set("image_repository", "pallet-registry:5000/" + orgId + "/" + appId)
                        .set("image_tag", SHA.substring(0, 12) + "-" + buildId.substring(0, 8))
                        .set("image_digest", DIGEST);
            case "FAILED" -> row.set("started_at", AT).set("finished_at", AT).set("failure_code", "BUILD_FAILED");
            default -> throw new IllegalArgumentException(status);
        }
        return row;
    }

    private final class BuildRow {

        private final Map<String, Object> columns = new LinkedHashMap<>();

        BuildRow set(String column, Object value) {
            columns.put(column, value);
            return this;
        }

        String insert() {
            List<Object> values = new ArrayList<>(columns.values());
            jdbc.update(
                    "insert into build.builds (" + String.join(", ", columns.keySet()) + ") values ("
                            + String.join(", ", Collections.nCopies(columns.size(), "?")) + ")",
                    values.toArray());
            return (String) columns.get("build_id");
        }
    }

    private static void assertRejected(Runnable statement, String constraint) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }
}
