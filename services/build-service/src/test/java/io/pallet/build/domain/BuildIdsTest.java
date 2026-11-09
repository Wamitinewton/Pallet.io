package io.pallet.build.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.build.domain.BuildIds.EventKind;
import io.pallet.common.test.annotations.UnitTest;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class BuildIdsTest {

    private static final String BUILD_ID = "3f0a5b8c-1d2e-4f60-8a9b-0c1d2e3f4a5b";

    @ParameterizedTest
    @EnumSource(EventKind.class)
    void anEventIdIsTheSameEveryTimeAndAVersion5Uuid(EventKind kind) {
        UUID first = BuildIds.eventId(BUILD_ID, kind);

        assertThat(BuildIds.eventId(BUILD_ID, kind)).isEqualTo(first);
        assertThat(first.version()).isEqualTo(5);
        assertThat(first.variant()).isEqualTo(2);
    }

    @Test
    void eachKindOfEventHasItsOwnId() {
        assertThat(Arrays.stream(EventKind.values()).map(kind -> BuildIds.eventId(BUILD_ID, kind)))
                .doesNotHaveDuplicates()
                .hasSize(EventKind.values().length);
    }

    @Test
    void eachBuildHasItsOwnEventIds() {
        String other = UUID.randomUUID().toString();

        assertThat(BuildIds.eventId(other, EventKind.FAILED))
                .isNotEqualTo(BuildIds.eventId(BUILD_ID, EventKind.FAILED));
    }

    @Test
    void theNamespaceIsPinnedSoPublishedIdsNeverChange() {
        assertThat(BuildIds.eventId(BUILD_ID, EventKind.STARTED))
                .isEqualTo(BuildIds.nameBased(BuildIds.NAMESPACE, BUILD_ID + ":started"));
        assertThat(BuildIds.NAMESPACE).isEqualTo(UUID.fromString("99cbc45d-2c80-4933-b20e-9e0cf12b949a"));
    }

    @Test
    void theNamesAreDerivedFromTheBuildId() {
        assertThat(BuildIds.jobName(BUILD_ID)).isEqualTo("build-" + BUILD_ID);
        assertThat(BuildIds.sourceSecretName(BUILD_ID)).isEqualTo("build-" + BUILD_ID + "-source");
    }

    @Test
    void anyUuidBuildIdFormsValidKubernetesNames() {
        for (int i = 0; i < 1000; i++) {
            String buildId = UUID.randomUUID().toString();

            assertThat(BuildIds.jobName(buildId)).hasSizeLessThanOrEqualTo(BuildIds.MAX_OBJECT_NAME_LENGTH);
            assertThat(BuildIds.sourceSecretName(buildId))
                    .hasSizeLessThanOrEqualTo(BuildIds.MAX_OBJECT_NAME_LENGTH)
                    .matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?");
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "3F0A5B8C-1D2E-4F60-8A9B-0C1D2E3F4A5B",
                "build_1",
                "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
            })
    void aBuildIdThatCannotNameAKubernetesObjectIsRefused(String buildId) {
        assertThatThrownBy(() -> BuildIds.jobName(buildId)).isInstanceOf(IllegalArgumentException.class);
    }
}
