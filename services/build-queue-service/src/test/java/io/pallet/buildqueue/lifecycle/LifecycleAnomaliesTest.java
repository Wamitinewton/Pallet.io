package io.pallet.buildqueue.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.lifecycle.BuildReport.Type;
import io.pallet.buildqueue.lifecycle.LifecycleAnomalies.Kind;
import io.pallet.common.test.annotations.UnitTest;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** ARCHITECTURE.md §Build lifecycle: what a report that matched no transition means, by the status it found. */
@UnitTest
class LifecycleAnomaliesTest {

    static Stream<Arguments> classification() {
        return Stream.of(
                Arguments.of(Type.STARTED, BuildStatus.SUCCEEDED, Kind.OUT_OF_ORDER),
                Arguments.of(Type.STARTED, BuildStatus.FAILED, Kind.OUT_OF_ORDER),
                Arguments.of(Type.STARTED, BuildStatus.RUNNING, Kind.OUT_OF_ORDER),
                Arguments.of(Type.STARTED, BuildStatus.EXPIRED, Kind.LATE_START),
                Arguments.of(Type.STARTED, BuildStatus.QUEUED, Kind.IMPOSSIBLE),
                Arguments.of(Type.STARTED, BuildStatus.SUPERSEDED, Kind.IMPOSSIBLE),
                Arguments.of(Type.STARTED, BuildStatus.CANCELLED, Kind.IMPOSSIBLE),
                Arguments.of(Type.STARTED, BuildStatus.REJECTED, Kind.IMPOSSIBLE),
                Arguments.of(Type.SUCCEEDED, BuildStatus.SUCCEEDED, Kind.OUT_OF_ORDER),
                Arguments.of(Type.SUCCEEDED, BuildStatus.FAILED, Kind.OUT_OF_ORDER),
                Arguments.of(Type.FAILED, BuildStatus.SUCCEEDED, Kind.OUT_OF_ORDER),
                Arguments.of(Type.FAILED, BuildStatus.FAILED, Kind.OUT_OF_ORDER),
                Arguments.of(Type.SUCCEEDED, BuildStatus.QUEUED, Kind.IMPOSSIBLE),
                Arguments.of(Type.SUCCEEDED, BuildStatus.SUPERSEDED, Kind.IMPOSSIBLE),
                Arguments.of(Type.FAILED, BuildStatus.CANCELLED, Kind.IMPOSSIBLE),
                Arguments.of(Type.FAILED, BuildStatus.REJECTED, Kind.IMPOSSIBLE));
    }

    @ParameterizedTest(name = "{0} on {1} is {2}")
    @MethodSource("classification")
    void anUnmatchedReportIsClassifiedByTheStatusItFound(Type type, BuildStatus status, Kind kind) {
        assertThat(LifecycleAnomalies.classify(type, status)).isEqualTo(kind);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("kinds")
    void onlyContractViolationsAlert(Kind kind, boolean alerting) {
        assertThat(kind.logLevel().name()).isEqualTo(alerting ? "WARN" : "INFO");
    }

    static Stream<Arguments> kinds() {
        return Stream.of(
                Arguments.of(Kind.UNTRACKED, false),
                Arguments.of(Kind.OUT_OF_ORDER, false),
                Arguments.of(Kind.LATE_START, true),
                Arguments.of(Kind.LATE_RESULT, false),
                Arguments.of(Kind.IMPOSSIBLE, true));
    }
}
