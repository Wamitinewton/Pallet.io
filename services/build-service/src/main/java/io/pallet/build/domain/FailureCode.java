package io.pallet.build.domain;

import io.pallet.common.events.BuildFailed;

/**
 * Why a build failed, chosen from what failed and never from the build's output. Each carries the sentence published as
 * {@code BuildFailed.reason}, which every consumer of the topic can read.
 */
public enum FailureCode {
    SOURCE_UNAVAILABLE(
            BuildFailed.CODE_SOURCE_UNAVAILABLE,
            "The source could not be fetched because GitHub or the token service was unavailable."),
    SOURCE_NOT_FOUND(
            BuildFailed.CODE_SOURCE_NOT_FOUND, "The commit, repository, root directory or Dockerfile was not found."),
    DETECTION_FAILED(BuildFailed.CODE_DETECTION_FAILED, "No buildpack recognised the application."),
    BUILD_FAILED(BuildFailed.CODE_BUILD_FAILED, "The build exited with an error."),
    RESOURCE_LIMIT(BuildFailed.CODE_RESOURCE_LIMIT, "The build ran out of memory or disk space."),
    PUSH_FAILED(BuildFailed.CODE_PUSH_FAILED, "The image could not be pushed to the registry."),
    TIMEOUT(BuildFailed.CODE_TIMEOUT, "The build exceeded its maximum duration."),
    LAUNCH_FAILED(BuildFailed.CODE_LAUNCH_FAILED, "The build could not be launched."),
    INFRASTRUCTURE(BuildFailed.CODE_INFRASTRUCTURE, "The build was lost to an infrastructure failure."),
    APP_DELETED(BuildFailed.CODE_APP_DELETED, "The app was deleted."),
    ORG_DELETED(BuildFailed.CODE_ORG_DELETED, "The organization was deleted.");

    private final String wireName;
    private final String reason;

    FailureCode(String wireName, String reason) {
        this.wireName = wireName;
        this.reason = reason;
    }

    public String wireName() {
        return wireName;
    }

    public String reason() {
        return reason;
    }
}
