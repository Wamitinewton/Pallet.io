package io.pallet.gitintegration.checks;

import java.util.UUID;

/**
 * One row of {@code check_runs}: the check run for an app and commit, what it should show, and how far delivering that
 * to GitHub got. {@code desiredRevision} grows with every accepted change of {@code desired}; the row is pending while
 * {@code reportedRevision} differs from it.
 */
public record CheckRun(
        UUID appId,
        String commitSha,
        String orgId,
        Long checkRunId,
        DesiredCheck desired,
        int desiredRevision,
        CheckState lastReportedState,
        Integer reportedRevision,
        int attempts) {

    public boolean pending() {
        return reportedRevision == null || reportedRevision != desiredRevision;
    }
}
