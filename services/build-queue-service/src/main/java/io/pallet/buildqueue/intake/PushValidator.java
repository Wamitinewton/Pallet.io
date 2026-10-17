package io.pallet.buildqueue.intake;

import io.pallet.buildqueue.build.BuildIds;
import io.pallet.buildqueue.build.NewBuild;
import io.pallet.buildqueue.build.PriorityClass;
import io.pallet.common.events.GitPushReceived;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a {@code git.push.received} into the build it requests, or rejects it as malformed. Failure messages name the
 * field, never its value: the commit text is untrusted and is never logged.
 */
final class PushValidator {

    static final int MAX_ID_LENGTH = 64;
    static final int MAX_BRANCH_LENGTH = 255;
    static final int MAX_REPO_LENGTH = 255;
    static final int MAX_COMMIT_MESSAGE_LENGTH = 256;
    static final int MAX_COMMIT_AUTHOR_LENGTH = 100;

    private static final String EVENT = "GitPushReceived";
    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern TRACEPARENT = Pattern.compile("[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    private static final Set<String> PROVIDERS = Set.of(GitPushReceived.PROVIDER_GITHUB);

    private PushValidator() {}

    /**
     * @param traceparent the record's W3C {@code traceparent} header; anything not in that format is dropped, since a
     *     missing trace is better than a rejected build
     * @throws MalformedPushException if any field the build needs is missing or out of shape
     */
    static NewBuild validate(GitPushReceived push, String traceparent) {
        if (push.eventId() == null) {
            throw malformed("eventId is required");
        }
        String orgId = requireId("orgId", push.orgId());
        String appId = requireId("appId", push.appId());
        String branch = requireBranch(push.branch());
        if (push.commitSha() == null || !COMMIT_SHA.matcher(push.commitSha()).matches()) {
            throw malformed("commitSha must be 40 lowercase hex characters");
        }
        if (push.installationId() <= 0) {
            throw malformed("installationId must be positive");
        }
        if (push.repoId() <= 0) {
            throw malformed("repoId must be positive");
        }
        PriorityClass priorityClass =
                PriorityClass.of(push.trigger()).orElseThrow(() -> malformed("trigger is not a known trigger"));
        if (!PROVIDERS.contains(push.provider())) {
            throw malformed("provider is not a known provider");
        }
        String repo = requireText("repo", push.repo(), MAX_REPO_LENGTH);

        return new NewBuild(
                BuildIds.buildId(push.eventId()),
                orgId,
                appId,
                branch,
                push.commitSha(),
                push.eventId(),
                push.trigger(),
                priorityClass,
                push.provider(),
                push.installationId(),
                push.repoId(),
                repo,
                push.rootDirectory(),
                truncate(push.headCommitMessage(), MAX_COMMIT_MESSAGE_LENGTH),
                truncate(push.headCommitAuthor(), MAX_COMMIT_AUTHOR_LENGTH),
                traceparent != null && TRACEPARENT.matcher(traceparent).matches() ? traceparent : null);
    }

    /** Ids are compared exactly, so surrounding whitespace is malformed rather than trimmed. */
    private static String requireId(String field, String value) {
        requireText(field, value, MAX_ID_LENGTH);
        if (!value.strip().equals(value)) {
            throw malformed(field + " has surrounding whitespace");
        }
        return value;
    }

    private static String requireBranch(String branch) {
        requireText("branch", branch, MAX_BRANCH_LENGTH);
        if (branch.codePoints().anyMatch(Character::isISOControl)) {
            throw malformed("branch contains a control character");
        }
        return branch;
    }

    private static String requireText(String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw malformed(field + " is required");
        }
        if (value.length() > maxLength) {
            throw malformed(field + " is longer than " + maxLength + " characters");
        }
        return value;
    }

    /** Cuts on a code point boundary, so a surrogate pair is never split. */
    static String truncate(String value, int maxCodePoints) {
        if (value == null || value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private static MalformedPushException malformed(String problem) {
        return new MalformedPushException(EVENT + "." + problem);
    }
}
