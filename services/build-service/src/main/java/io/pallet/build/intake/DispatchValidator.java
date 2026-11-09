package io.pallet.build.intake;

import io.pallet.build.domain.NewBuild;
import io.pallet.common.events.BuildDispatched;
import io.pallet.common.events.GitPushReceived;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a {@code build.dispatched} into the build it asks for, or rejects it as malformed. Failure messages name the
 * field, never its value.
 */
final class DispatchValidator {

    static final int MAX_ID_LENGTH = 64;
    static final int MAX_BRANCH_LENGTH = 255;
    static final int MAX_REPO_LENGTH = 255;
    static final int MAX_ROOT_DIRECTORY_LENGTH = 255;

    private static final String EVENT = "BuildDispatched";
    private static final String REPOSITORY_ROOT = "";

    /** The queue's build ids are lowercase UUIDs; anything else couldn't name the build's Kubernetes objects. */
    private static final Pattern BUILD_ID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern TRACEPARENT = Pattern.compile("[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    private static final Set<String> PROVIDERS = Set.of(GitPushReceived.PROVIDER_GITHUB);

    private final Duration maxDurationCap;

    DispatchValidator(Duration maxDurationCap) {
        this.maxDurationCap = maxDurationCap;
    }

    /**
     * @param traceparent the record's W3C {@code traceparent} header; anything not in that format is dropped, since a
     *     missing trace is better than a dead-lettered build
     * @throws MalformedDispatchException if any field the build needs is missing or out of shape
     */
    NewBuild validate(BuildDispatched dispatch, String traceparent) {
        if (dispatch.eventId() == null) {
            throw malformed("eventId is required");
        }
        String orgId = requireId("orgId", dispatch.orgId());
        String buildId = requireId("buildId", dispatch.buildId());
        if (!BUILD_ID.matcher(buildId).matches()) {
            throw malformed("buildId must be a lowercase UUID");
        }
        String appId = requireId("appId", dispatch.appId());
        if (dispatch.attempt() < 1) {
            throw malformed("attempt must be at least 1");
        }
        String branch = requireText("branch", dispatch.branch(), MAX_BRANCH_LENGTH);
        if (dispatch.commitSha() == null
                || !COMMIT_SHA.matcher(dispatch.commitSha()).matches()) {
            throw malformed("commitSha must be 40 lowercase hex characters");
        }
        if (dispatch.pushEventId() == null) {
            throw malformed("pushEventId is required");
        }
        String trigger = requireText("trigger", dispatch.trigger(), MAX_ID_LENGTH);
        if (dispatch.provider() == null || !PROVIDERS.contains(dispatch.provider())) {
            throw malformed("provider is not a known provider");
        }
        if (dispatch.installationId() <= 0) {
            throw malformed("installationId must be positive");
        }
        if (dispatch.repoId() <= 0) {
            throw malformed("repoId must be positive");
        }
        String repo = requireText("repo", dispatch.repo(), MAX_REPO_LENGTH);
        String rootDirectory = rootDirectory(dispatch.rootDirectory());
        if (dispatch.startBy() == null) {
            throw malformed("startBy is required");
        }
        if (dispatch.maxDurationSeconds() <= 0) {
            throw malformed("maxDurationSeconds must be positive");
        }
        if (dispatch.maxDurationSeconds() > maxDurationCap.toSeconds()) {
            throw malformed("maxDurationSeconds is above this service's cap");
        }

        return new NewBuild(
                buildId,
                orgId,
                appId,
                dispatch.attempt(),
                branch,
                dispatch.commitSha(),
                dispatch.pushEventId(),
                trigger,
                dispatch.provider(),
                dispatch.installationId(),
                dispatch.repoId(),
                repo,
                rootDirectory,
                dispatch.startBy(),
                dispatch.maxDurationSeconds(),
                traceparent != null && TRACEPARENT.matcher(traceparent).matches() ? traceparent : null);
    }

    /**
     * The queue sends no root directory for a repository built from its root; {@code ""} and {@code "."} mean the
     * same. Anything else must stay inside the checkout.
     */
    private static String rootDirectory(String value) {
        if (value == null || value.isEmpty() || value.equals(".")) {
            return REPOSITORY_ROOT;
        }
        if (value.length() > MAX_ROOT_DIRECTORY_LENGTH) {
            throw malformed("rootDirectory is longer than " + MAX_ROOT_DIRECTORY_LENGTH + " characters");
        }
        if (value.startsWith("/")) {
            throw malformed("rootDirectory must be relative");
        }
        if (Arrays.asList(value.split("/", -1)).contains("..")) {
            throw malformed("rootDirectory must not contain a '..' segment");
        }
        if (hasControlCharacter(value)) {
            throw malformed("rootDirectory contains a control character");
        }
        return value;
    }

    /** Ids are compared exactly, so surrounding whitespace is malformed rather than trimmed. */
    private static String requireId(String field, String value) {
        requireText(field, value, MAX_ID_LENGTH);
        if (!value.strip().equals(value)) {
            throw malformed(field + " has surrounding whitespace");
        }
        return value;
    }

    private static String requireText(String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw malformed(field + " is required");
        }
        if (value.length() > maxLength) {
            throw malformed(field + " is longer than " + maxLength + " characters");
        }
        if (hasControlCharacter(value)) {
            throw malformed(field + " contains a control character");
        }
        return value;
    }

    private static boolean hasControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static MalformedDispatchException malformed(String problem) {
        return new MalformedDispatchException(EVENT + "." + problem);
    }
}
