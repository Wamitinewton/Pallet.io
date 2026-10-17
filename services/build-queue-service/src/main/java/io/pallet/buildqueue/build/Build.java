package io.pallet.buildqueue.build;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** A build as the queue last recorded it. Read side only: status changes are guarded updates, never a save. */
@Entity
@Immutable
@Table(name = "builds")
public class Build {

    @Id
    @Column(name = "build_id")
    private UUID buildId;

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

    @Column(name = "priority_class", nullable = false)
    private short priorityClass;

    @Column(nullable = false)
    private String provider;

    @Column(name = "installation_id", nullable = false)
    private long installationId;

    @Column(name = "repo_id", nullable = false)
    private long repoId;

    @Column(nullable = false)
    private String repo;

    @Column(name = "root_directory")
    private String rootDirectory;

    @Column(name = "head_commit_message")
    private String headCommitMessage;

    @Column(name = "head_commit_author")
    private String headCommitAuthor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BuildStatus status;

    @Column(name = "status_reason")
    private String statusReason;

    @Column(name = "superseded_by")
    private UUID supersededBy;

    @Column(nullable = false)
    private short attempt;

    @Column(name = "enqueued_at", nullable = false)
    private Instant enqueuedAt;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "start_deadline")
    private Instant startDeadline;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "cancelled_by")
    private String cancelledBy;

    @Column
    private String traceparent;

    @Column(nullable = false)
    private long version;

    protected Build() {}

    public UUID buildId() {
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

    public PriorityClass priorityClass() {
        return PriorityClass.fromRank(priorityClass);
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

    public String headCommitMessage() {
        return headCommitMessage;
    }

    public String headCommitAuthor() {
        return headCommitAuthor;
    }

    public BuildStatus status() {
        return status;
    }

    public String statusReason() {
        return statusReason;
    }

    public UUID supersededBy() {
        return supersededBy;
    }

    public int attempt() {
        return attempt;
    }

    public Instant enqueuedAt() {
        return enqueuedAt;
    }

    public Instant dispatchedAt() {
        return dispatchedAt;
    }

    public Instant startDeadline() {
        return startDeadline;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant leaseExpiresAt() {
        return leaseExpiresAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public String cancelledBy() {
        return cancelledBy;
    }

    public String traceparent() {
        return traceparent;
    }

    public long version() {
        return version;
    }
}
