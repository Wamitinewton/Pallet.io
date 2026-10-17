package io.pallet.buildqueue.api.dto;

import io.pallet.buildqueue.build.Build;

/**
 * Leaves out what the org has no use for or must not see: the installation and repository ids, the trace context, and
 * who cancelled the build, which the audit log holds.
 */
public final class BuildMapper {

    private BuildMapper() {}

    public static BuildDto toDto(Build build) {
        return new BuildDto(
                build.buildId(),
                build.appId(),
                build.branch(),
                build.commitSha(),
                build.trigger(),
                build.status(),
                build.statusReason(),
                build.supersededBy(),
                build.attempt(),
                build.enqueuedAt(),
                build.dispatchedAt(),
                build.startedAt(),
                build.finishedAt(),
                build.headCommitMessage(),
                build.headCommitAuthor());
    }
}
