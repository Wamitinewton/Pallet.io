package io.pallet.buildqueue.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.buildqueue.lifecycle.BuildReport.Type;
import io.pallet.common.inbox.NonRetryableEventException;
import io.pallet.common.test.annotations.UnitTest;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@UnitTest
class BuildReportTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID BUILD_ID = UUID.randomUUID();

    @Test
    void aWellFormedReportNamesItsBuild() {
        BuildReport report = BuildReport.of(Type.SUCCEEDED, EVENT_ID, "org-a", "app-1", BUILD_ID.toString());

        assertThat(report).isEqualTo(new BuildReport(Type.SUCCEEDED, EVENT_ID, "org-a", "app-1", BUILD_ID));
    }

    static Stream<Arguments> malformed() {
        String buildId = BUILD_ID.toString();
        return Stream.of(
                Arguments.of("eventId missing", null, "org-a", "app-1", buildId, "eventId"),
                Arguments.of("orgId missing", EVENT_ID, null, "app-1", buildId, "orgId"),
                Arguments.of("orgId blank", EVENT_ID, " ", "app-1", buildId, "orgId"),
                Arguments.of("orgId padded", EVENT_ID, " org-a", "app-1", buildId, "orgId"),
                Arguments.of("appId missing", EVENT_ID, "org-a", null, buildId, "appId"),
                Arguments.of("appId too long", EVENT_ID, "org-a", "a".repeat(65), buildId, "appId"),
                Arguments.of("buildId missing", EVENT_ID, "org-a", "app-1", null, "buildId"),
                Arguments.of("buildId not a UUID", EVENT_ID, "org-a", "app-1", "build-1", "buildId"),
                Arguments.of("buildId uppercase", EVENT_ID, "org-a", "app-1", buildId.toUpperCase(), "buildId"),
                Arguments.of("buildId unpadded", EVENT_ID, "org-a", "app-1", "1-2-3-4-5", "buildId"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformed")
    void aMalformedReportIsNeverRetried(
            String description, UUID eventId, String orgId, String appId, String buildId, String field) {
        assertThatThrownBy(() -> BuildReport.of(Type.STARTED, eventId, orgId, appId, buildId))
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessageStartingWith("BuildStarted." + field);
    }
}
