package io.pallet.build.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every write to a build's status. Each is one guarded statement that names the statuses it may move from, and the
 * caller acts on whether it matched. Whether a build can still start in time is decided against database
 * {@code now()}, never an application clock.
 */
@Component
public class BuildTransitions {

    /** True while the build can still be started at least {@code :marginMillis} before its {@code start_by}. */
    private static final String IN_TIME =
            "CAST(:startBy AS timestamptz) > now() + :marginMillis * interval '1 millisecond'";

    private static final String COLUMNS = """
            build_id, org_id, app_id, branch, commit_sha, push_event_id, trigger, attempt, provider, installation_id,
            repo_id, repo, root_directory, start_by, max_duration_seconds, strategy_setting, dockerfile_path,
            job_name, traceparent""";

    private static final String VALUES = """
            :buildId, :orgId, :appId, :branch, :commitSha, :pushEventId, :trigger, :attempt, :provider, :installationId,
            :repoId, :repo, :rootDirectory, :startBy, :maxDurationSeconds, :strategySetting, :dockerfilePath,
            :jobName, :traceparent""";

    private static final String ACCEPT =
            """
            INSERT INTO build.builds ({columns}, status, drop_count)
            VALUES ({values},
                    CASE WHEN {inTime} THEN 'ACCEPTED' ELSE 'DROPPED' END,
                    CASE WHEN {inTime} THEN 0 ELSE 1 END)
            ON CONFLICT (build_id) DO NOTHING
            RETURNING status
            """.replace("{columns}", COLUMNS).replace("{values}", VALUES).replace("{inTime}", IN_TIME);

    private static final String REFRESH = """
            UPDATE build.builds
               SET attempt = :attempt,
                   start_by = :startBy,
                   status = CASE WHEN {inTime} THEN 'ACCEPTED' ELSE 'DROPPED' END,
                   drop_count = drop_count + CASE WHEN {inTime} THEN 0 ELSE 1 END,
                   updated_at = now()
             WHERE build_id = :buildId AND org_id = :orgId
               AND status IN ('ACCEPTED', 'DROPPED') AND attempt < :attempt
            RETURNING status
            """.replace("{inTime}", IN_TIME);

    private static final String FAIL_AT_INTAKE =
            """
            INSERT INTO build.builds ({columns}, status, failure_code, failure_reason, finished_at)
            VALUES ({values}, 'FAILED', :failureCode, :failureReason, now())
            ON CONFLICT (build_id) DO NOTHING
            """.replace("{columns}", COLUMNS).replace("{values}", VALUES);

    private final JdbcClient jdbc;

    BuildTransitions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records a new build as {@code ACCEPTED}, or as {@code DROPPED} when it can no longer start {@code margin} before
     * its {@code startBy}.
     *
     * @return the status recorded, or empty when the build was already recorded
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BuildStatus> accept(NewBuild build, SettingsSnapshot settings, Duration margin) {
        return withBuild(jdbc.sql(ACCEPT), build, settings)
                .param("marginMillis", margin.toMillis())
                .query(String.class)
                .optional()
                .map(BuildStatus::valueOf);
    }

    /**
     * Gives a build that hasn't started the dispatch's later attempt and new {@code startBy}, waiting again if it can
     * still start in time. Its settings snapshot is left as the first dispatch recorded it.
     *
     * @return the status it now has, or empty when it had started or already had this attempt or a later one
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BuildStatus> refresh(NewBuild build, Duration margin) {
        return jdbc.sql(REFRESH)
                .param("buildId", build.buildId())
                .param("orgId", build.orgId())
                .param("attempt", build.attempt())
                .param("startBy", utc(build.startBy()))
                .param("marginMillis", margin.toMillis())
                .query(String.class)
                .optional()
                .map(BuildStatus::valueOf);
    }

    /**
     * Records a dispatch for a deleted app or org as already {@code FAILED}.
     *
     * @return false when the build was already recorded, in which case nothing changed and nothing may be published
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean failAtIntake(NewBuild build, SettingsSnapshot settings, FailureCode code) {
        return withBuild(jdbc.sql(FAIL_AT_INTAKE), build, settings)
                        .param("failureCode", code.name())
                        .param("failureReason", code.reason())
                        .update()
                == 1;
    }

    private static JdbcClient.StatementSpec withBuild(
            JdbcClient.StatementSpec statement, NewBuild build, SettingsSnapshot settings) {
        return statement
                .param("buildId", build.buildId())
                .param("orgId", build.orgId())
                .param("appId", build.appId())
                .param("branch", build.branch())
                .param("commitSha", build.commitSha())
                .param("pushEventId", build.pushEventId())
                .param("trigger", build.trigger())
                .param("attempt", build.attempt())
                .param("provider", build.provider())
                .param("installationId", build.installationId())
                .param("repoId", build.repoId())
                .param("repo", build.repo())
                .param("rootDirectory", build.rootDirectory())
                .param("startBy", utc(build.startBy()))
                .param("maxDurationSeconds", build.maxDurationSeconds())
                .param("strategySetting", settings.strategySetting().name())
                .param("dockerfilePath", settings.dockerfilePath())
                .param("jobName", BuildIds.jobName(build.buildId()))
                .param("traceparent", build.traceparent());
    }

    /** Bound as {@code timestamptz}, so Postgres never reinterprets it in the session's time zone. */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
