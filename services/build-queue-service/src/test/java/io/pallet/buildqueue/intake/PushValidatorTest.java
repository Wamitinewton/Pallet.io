package io.pallet.buildqueue.intake;

import static io.pallet.buildqueue.support.PushEvents.push;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.buildqueue.build.BuildIds;
import io.pallet.buildqueue.build.NewBuild;
import io.pallet.buildqueue.build.PriorityClass;
import io.pallet.buildqueue.support.PushEvents.Builder;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.inbox.NonRetryableEventException;
import io.pallet.common.test.annotations.UnitTest;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@UnitTest
class PushValidatorTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void aWellFormedPushBecomesTheBuildItRequests() {
        GitPushReceived event = valid().trigger(GitPushReceived.TRIGGER_MANUAL).build();

        NewBuild build = PushValidator.validate(event, TRACEPARENT);

        assertThat(build.buildId()).isEqualTo(BuildIds.buildId(event.eventId()));
        assertThat(build.pushEventId()).isEqualTo(event.eventId());
        assertThat(build.orgId()).isEqualTo("org-a");
        assertThat(build.appId()).isEqualTo("app-1");
        assertThat(build.branch()).isEqualTo(event.branch());
        assertThat(build.commitSha()).isEqualTo(event.commitSha());
        assertThat(build.trigger()).isEqualTo(GitPushReceived.TRIGGER_MANUAL);
        assertThat(build.priorityClass()).isEqualTo(PriorityClass.INTERACTIVE);
        assertThat(build.provider()).isEqualTo(GitPushReceived.PROVIDER_GITHUB);
        assertThat(build.installationId()).isEqualTo(event.installationId());
        assertThat(build.repoId()).isEqualTo(event.repoId());
        assertThat(build.repo()).isEqualTo(event.repo());
        assertThat(build.rootDirectory()).isEqualTo(event.rootDirectory());
        assertThat(build.headCommitMessage()).isEqualTo(event.headCommitMessage());
        assertThat(build.headCommitAuthor()).isEqualTo(event.headCommitAuthor());
        assertThat(build.traceparent()).isEqualTo(TRACEPARENT);
    }

    static Stream<Arguments> malformed() {
        return Stream.of(
                rejects("eventId missing", b -> b.eventId(null), "eventId"),
                rejects("orgId blank", b -> b.orgId(" "), "orgId"),
                rejects("orgId missing", b -> b.orgId(null), "orgId"),
                rejects("orgId too long", b -> b.orgId("o".repeat(65)), "orgId"),
                rejects("orgId padded", b -> b.orgId(" org-a"), "orgId"),
                rejects("appId blank", b -> b.appId(""), "appId"),
                rejects("branch blank", b -> b.branch("  "), "branch"),
                rejects("branch too long", b -> b.branch("b".repeat(256)), "branch"),
                rejects("branch with a newline", b -> b.branch("main\nevil"), "branch"),
                rejects("branch with a NUL", b -> b.branch("main\0"), "branch"),
                rejects("sha uppercase", b -> b.commitSha("ABCDEF0123456789ABCDEF0123456789ABCDEF01"), "commitSha"),
                rejects("sha short", b -> b.commitSha("abc123"), "commitSha"),
                rejects("sha missing", b -> b.commitSha(null), "commitSha"),
                rejects("installationId zero", b -> b.installationId(0), "installationId"),
                rejects("repoId negative", b -> b.repoId(-1), "repoId"),
                rejects("trigger unknown", b -> b.trigger("SCHEDULED"), "trigger"),
                rejects("trigger missing", b -> b.trigger(null), "trigger"),
                rejects("provider unknown", b -> b.provider("GITLAB"), "provider"),
                rejects("repo missing", b -> b.repo(null), "repo"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformed")
    void aMalformedPushIsRejectedAsNonRetryableNamingTheField(
            String name, UnaryOperator<Builder> breakIt, String field) {
        GitPushReceived event = breakIt.apply(valid()).build();

        assertThatThrownBy(() -> PushValidator.validate(event, null))
                .isInstanceOf(MalformedPushException.class)
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessageContaining("GitPushReceived." + field);
    }

    @Test
    void theFailureNeverQuotesTheValue() {
        GitPushReceived event = valid().branch("secret-branch\n").build();

        assertThatThrownBy(() -> PushValidator.validate(event, null)).hasMessageNotContaining("secret-branch");
    }

    @Test
    void commitTextIsTruncatedOnCodePointBoundaries() {
        String emoji = "🚀";
        GitPushReceived event = valid().headCommitMessage("m".repeat(255) + emoji + "tail")
                .headCommitAuthor(emoji.repeat(150))
                .build();

        NewBuild build = PushValidator.validate(event, null);

        assertThat(build.headCommitMessage()).isEqualTo("m".repeat(255) + emoji);
        assertThat(build.headCommitAuthor()).isEqualTo(emoji.repeat(100));
    }

    @Test
    void commitTextWithinBoundsAndAbsentTextAreKeptAsIs() {
        NewBuild build = PushValidator.validate(
                valid().headCommitMessage(null)
                        .headCommitAuthor("a".repeat(100))
                        .build(),
                null);

        assertThat(build.headCommitMessage()).isNull();
        assertThat(build.headCommitAuthor()).isEqualTo("a".repeat(100));
    }

    @Test
    void aMalformedTraceparentIsDroppedRatherThanRejectingTheBuild() {
        assertThat(PushValidator.validate(valid().build(), "not-a-traceparent").traceparent())
                .isNull();
        assertThat(PushValidator.validate(valid().build(), null).traceparent()).isNull();
    }

    @Test
    void automaticTriggersGetTheLowerPriorityClass() {
        assertThat(PushValidator.validate(
                                valid().trigger(GitPushReceived.TRIGGER_RECONCILED)
                                        .build(),
                                null)
                        .priorityClass())
                .isEqualTo(PriorityClass.AUTOMATIC);
    }

    private static Builder valid() {
        return push("org-a", "app-1");
    }

    private static Arguments rejects(String name, UnaryOperator<Builder> breakIt, String field) {
        return Arguments.of(name, breakIt, field);
    }
}
