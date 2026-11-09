package io.pallet.build.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** A build as this service last recorded it. Read side only: status changes are guarded updates, never a save. */
@Entity
@Immutable
@Table(name = "builds")
public class Build {

    @Id
    @Column(name = "build_id")
    private String buildId;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    @Column(name = "app_id", nullable = false)
    private String appId;

    @Column(nullable = false)
    private String branch;

    @Column(name = "commit_sha", nullable = false)
    private String commitSha;

    @Column(name = "push_event_id", nullable = false)
    private UUID pushEventId;

    @Column(nullable = false)
    private String trigger;

    @Column(nullable = false)
    private int attempt;

    @Column(nullable = false)
    private String provider;

    @Column(name = "installation_id", nullable = false)
    private long installationId;

    @Column(name = "repo_id", nullable = false)
    private long repoId;

    @Column(nullable = false)
    private String repo;

    @Column(name = "root_directory", nullable = false)
    private String rootDirectory;

    @Column(name = "start_by", nullable = false)
    private Instant startBy;

    @Column(name = "max_duration_seconds", nullable = false)
    private long maxDurationSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_setting", nullable = false)
    private Strategy strategySetting;

    @Column(name = "dockerfile_path", nullable = false)
    private String dockerfilePath;

    @Enumerated(EnumType.STRING)
    @Column
    private Strategy strategy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BuildStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code")
    private FailureCode failureCode;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "drop_count", nullable = false)
    private int dropCount;

    @Column(name = "job_name", nullable = false)
    private String jobName;

    @Column(name = "image_repository")
    private String imageRepository;

    @Column(name = "image_tag")
    private String imageTag;

    @Column(name = "image_digest")
    private String imageDigest;

    @Column(name = "log_bytes", nullable = false)
    private long logBytes;

    @Column(name = "log_truncated", nullable = false)
    private boolean logTruncated;

    @Column(name = "log_complete", nullable = false)
    private boolean logComplete;

    @Column
    private String traceparent;

    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Build() {}

    public String buildId() {
        return buildId;
    }

    public String orgId() {
        return orgId;
    }

    public String appId() {
        return appId;
    }

    public String branch() {
        return branch;
    }

    public String commitSha() {
        return commitSha;
    }

    public UUID pushEventId() {
        return pushEventId;
    }

    public String trigger() {
        return trigger;
    }

    public int attempt() {
        return attempt;
    }

    public String provider() {
        return provider;
    }

    public long installationId() {
        return installationId;
    }

    public long repoId() {
        return repoId;
    }

    public String repo() {
        return repo;
    }

    public String rootDirectory() {
        return rootDirectory;
    }

    public Instant startBy() {
        return startBy;
    }

    public long maxDurationSeconds() {
        return maxDurationSeconds;
    }

    public Strategy strategySetting() {
        return strategySetting;
    }

    public String dockerfilePath() {
        return dockerfilePath;
    }

    public Strategy strategy() {
        return strategy;
    }

    public BuildStatus status() {
        return status;
    }

    public FailureCode failureCode() {
        return failureCode;
    }

    public String failureReason() {
        return failureReason;
    }

    public int dropCount() {
        return dropCount;
    }

    public String jobName() {
        return jobName;
    }

    public String imageRepository() {
        return imageRepository;
    }

    public String imageTag() {
        return imageTag;
    }

    public String imageDigest() {
        return imageDigest;
    }

    public long logBytes() {
        return logBytes;
    }

    public boolean logTruncated() {
        return logTruncated;
    }

    public boolean logComplete() {
        return logComplete;
    }

    public String traceparent() {
        return traceparent;
    }

    public Instant acceptedAt() {
        return acceptedAt;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
