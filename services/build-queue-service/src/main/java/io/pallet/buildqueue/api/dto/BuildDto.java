package io.pallet.buildqueue.api.dto;

import io.pallet.buildqueue.build.BuildStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * A build as its org sees it. {@code headCommitMessage} and {@code headCommitAuthor} are untrusted text from the push,
 * kept only for display.
 */
@Schema(description = "A build as its org sees it.")
public record BuildDto(
        @Schema(example = "3f1c9a52-7d4e-5b8a-9c21-0e6f4d2b7a18")
        UUID buildId,

        @Schema(example = "8b2e4f10-3c6d-4a9e-b1f7-5d0c2a9e6b34")
        String appId,

        @Schema(example = "main") String branch,

        @Schema(example = "0123456789abcdef0123456789abcdef01234567")
        String commitSha,

        @Schema(
                description = "What asked for the build. MANUAL: someone pressed Build. LINKED: the repository was "
                        + "just linked with deploy now. WEBHOOK: a push. RECONCILED: a missed push found later. "
                        + "MANUAL and LINKED go before WEBHOOK and RECONCILED within the org.",
                allowableValues = {"MANUAL", "LINKED", "WEBHOOK", "RECONCILED"},
                example = "WEBHOOK")
        String trigger,

        @Schema(description = BuildDto.STATUS_MEANINGS) BuildStatus status,

        @Schema(
                nullable = true,
                description = "Why the build reached its status: USER_CANCELLED, APP_DELETED or ORG_DELETED for "
                        + "CANCELLED; SUPERSEDED; QUEUE_FULL for REJECTED; QUEUE_TIMEOUT, START_TIMEOUT or "
                        + "LEASE_EXPIRED for EXPIRED; build-service's own failure message for FAILED. Null when "
                        + "there is nothing to explain.",
                example = "USER_CANCELLED")
        String statusReason,

        @Schema(nullable = true, description = "The build that replaced this one; set only when SUPERSEDED.")
        UUID supersededBy,

        @Schema(
                description = "How many times the build was handed to build-service: 0 while waiting, 1 once "
                        + "dispatched, more only when build-service missed a start deadline and it was handed "
                        + "over again.",
                example = "1")
        int attempt,

        Instant enqueuedAt,
        @Schema(nullable = true) Instant dispatchedAt,
        @Schema(nullable = true) Instant startedAt,

        @Schema(nullable = true, description = "When the build reached a final status; null before.")
        Instant finishedAt,

        @Schema(
                nullable = true,
                description = "The head commit's message as pushed. Untrusted text: render it escaped.",
                example = "Fix the login redirect")
        String headCommitMessage,

        @Schema(
                nullable = true,
                description = "The head commit's author as pushed. Untrusted text: render it escaped.",
                example = "octocat")
        String headCommitAuthor) {

    static final String STATUS_MEANINGS = "QUEUED: waiting for a slot. DISPATCHED: handed to build-service, "
            + "waiting for it to start. RUNNING: build-service started it. SUCCEEDED: build-service reported "
            + "success; final. FAILED: build-service reported a failure; final. SUPERSEDED: a newer commit for the same app and branch replaced this one "
            + "before it started; supersededBy names it. CANCELLED: taken out of the queue before it started, by a "
            + "person or because its app or org was deleted. REJECTED: never queued, because the org's backlog was "
            + "full. EXPIRED: the queue stopped waiting for it (queued too long, never started, or no result in "
            + "time); the build may still report a result and move to SUCCEEDED or FAILED.";
}
