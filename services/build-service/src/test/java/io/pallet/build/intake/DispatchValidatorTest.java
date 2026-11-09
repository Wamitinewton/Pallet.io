package io.pallet.build.intake;

import static io.pallet.build.support.Dispatches.dispatch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.pallet.build.domain.NewBuild;
import io.pallet.build.support.Dispatches.Builder;
import io.pallet.common.events.BuildDispatched;
import io.pallet.common.test.annotations.UnitTest;
import java.time.Duration;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class DispatchValidatorTest {

    private static final Duration CAP = Duration.ofHours(2);
    private static final String TRACEPARENT = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";

    private final DispatchValidator validator = new DispatchValidator(CAP);

    @Test
    void aWellFormedDispatchBecomesTheBuildItDescribes() {
        BuildDispatched event = dispatch("org-a", "app-1").attempt(3).build();

        NewBuild build = validator.validate(event, TRACEPARENT);

        assertThat(build.buildId()).isEqualTo(event.buildId());
        assertThat(build.orgId()).isEqualTo("org-a");
        assertThat(build.appId()).isEqualTo("app-1");
        assertThat(build.attempt()).isEqualTo(3);
        assertThat(build.branch()).isEqualTo(event.branch());
        assertThat(build.commitSha()).isEqualTo(event.commitSha());
        assertThat(build.pushEventId()).isEqualTo(event.pushEventId());
        assertThat(build.trigger()).isEqualTo(event.trigger());
        assertThat(build.provider()).isEqualTo("GITHUB");
        assertThat(build.installationId()).isEqualTo(event.installationId());
        assertThat(build.repoId()).isEqualTo(event.repoId());
        assertThat(build.repo()).isEqualTo(event.repo());
        assertThat(build.rootDirectory()).isEqualTo(event.rootDirectory());
        assertThat(build.startBy()).isEqualTo(event.startBy());
        assertThat(build.maxDurationSeconds()).isEqualTo(event.maxDurationSeconds());
        assertThat(build.traceparent()).isEqualTo(TRACEPARENT);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "."})
    void anAbsentEmptyOrDotRootDirectoryIsTheRepositoryRoot(String rootDirectory) {
        NewBuild build = validator.validate(
                dispatch("org-a", "app-1").rootDirectory(rootDirectory).build(), null);

        assertThat(build.rootDirectory()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"web", "services/web", "a/b.c/..d", "./web"})
    void aRelativeRootDirectoryInsideTheCheckoutIsKept(String rootDirectory) {
        assertThat(validator
                        .validate(
                                dispatch("org-a", "app-1")
                                        .rootDirectory(rootDirectory)
                                        .build(),
                                null)
                        .rootDirectory())
                .isEqualTo(rootDirectory);
    }

    @Test
    void aMaximumDurationAtTheCapIsAccepted() {
        assertThat(validator
                        .validate(
                                dispatch("org-a", "app-1")
                                        .maxDurationSeconds(CAP.toSeconds())
                                        .build(),
                                null)
                        .maxDurationSeconds())
                .isEqualTo(CAP.toSeconds());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-traceparent", "00-xyz-0123456789abcdef-01", ""})
    void anUnusableTraceparentIsDroppedRatherThanRejected(String traceparent) {
        assertThat(validator
                        .validate(dispatch("org-a", "app-1").build(), traceparent)
                        .traceparent())
                .isNull();
    }

    static Stream<Arguments> malformed() {
        return Stream.of(
                rule("eventId", "eventId is required", b -> b.eventId(null)),
                rule("blank orgId", "orgId is required", b -> b.orgId("  ")),
                rule("padded orgId", "orgId has surrounding whitespace", b -> b.orgId(" org-secret-a ")),
                rule("long orgId", "orgId is longer than", b -> b.orgId("o".repeat(65))),
                rule("missing buildId", "buildId is required", b -> b.buildId(null)),
                rule(
                        "upper-case buildId",
                        "buildId must be a lowercase UUID",
                        b -> b.buildId("3F0A5B8C-1D2E-4F60-8A9B-0C1D2E3F4A5B")),
                rule("non-UUID buildId", "buildId must be a lowercase UUID", b -> b.buildId("build-secret-1")),
                rule("blank appId", "appId is required", b -> b.appId("")),
                rule("zero attempt", "attempt must be at least 1", b -> b.attempt(0)),
                rule("negative attempt", "attempt must be at least 1", b -> b.attempt(-2)),
                rule("blank branch", "branch is required", b -> b.branch(" ")),
                rule("branch with a newline", "branch contains a control character", b -> b.branch("main\nsecret")),
                rule("long branch", "branch is longer than", b -> b.branch("b".repeat(256))),
                rule("missing commitSha", "commitSha must be 40", b -> b.commitSha(null)),
                rule("short commitSha", "commitSha must be 40", b -> b.commitSha("abc123")),
                rule("upper-case commitSha", "commitSha must be 40", b -> b.commitSha("ABCDEF".repeat(6) + "ABCD")),
                rule("missing pushEventId", "pushEventId is required", b -> b.pushEventId(null)),
                rule("blank trigger", "trigger is required", b -> b.trigger("")),
                rule("unknown provider", "provider is not a known provider", b -> b.provider("GITLAB-SECRET")),
                rule("missing provider", "provider is not a known provider", b -> b.provider(null)),
                rule("zero installationId", "installationId must be positive", b -> b.installationId(0)),
                rule("negative repoId", "repoId must be positive", b -> b.repoId(-5)),
                rule("blank repo", "repo is required", b -> b.repo(" ")),
                rule("absolute rootDirectory", "rootDirectory must be relative", b -> b.rootDirectory("/etc/secret")),
                rule("parent rootDirectory", "rootDirectory must not contain", b -> b.rootDirectory("..")),
                rule("escaping rootDirectory", "rootDirectory must not contain", b -> b.rootDirectory("web/../../x")),
                rule("trailing parent", "rootDirectory must not contain", b -> b.rootDirectory("web/..")),
                rule("long rootDirectory", "rootDirectory is longer than", b -> b.rootDirectory("r".repeat(256))),
                rule(
                        "rootDirectory with a control character",
                        "rootDirectory contains a control character",
                        b -> b.rootDirectory("web\u0000x")),
                rule("missing startBy", "startBy is required", b -> b.startBy(null)),
                rule("zero maxDuration", "maxDurationSeconds must be positive", b -> b.maxDurationSeconds(0)),
                rule(
                        "maxDuration above the cap",
                        "maxDurationSeconds is above",
                        b -> b.maxDurationSeconds(CAP.toSeconds() + 1)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformed")
    void aMalformedDispatchIsRejectedNamingTheFieldButNotItsValue(
            String name, String expectedMessage, UnaryOperator<Builder> change) {
        BuildDispatched event = change.apply(dispatch("org-a", "app-1")).build();

        MalformedDispatchException rejected =
                catchThrowableOfType(MalformedDispatchException.class, () -> validator.validate(event, null));

        assertThat(rejected).as(name).isNotNull();
        assertThat(rejected.getMessage()).startsWith("BuildDispatched.").contains(expectedMessage);
        for (String value : new String[] {
            "org-secret-a", "build-secret-1", "secret", "GITLAB-SECRET", "/etc/secret", "abc123", "ABCDEF"
        }) {
            assertThat(rejected.getMessage()).doesNotContain(value);
        }
    }

    private static Arguments rule(String name, String expectedMessage, UnaryOperator<Builder> change) {
        return Arguments.of(name, expectedMessage, change);
    }
}
