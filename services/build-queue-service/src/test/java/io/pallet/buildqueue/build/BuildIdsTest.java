package io.pallet.buildqueue.build;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.UnitTest;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@UnitTest
class BuildIdsTest {

    private static final UUID PUSH_EVENT_ID = UUID.fromString("6f1c2d8e-4b7a-4c39-9e51-2a7d0b3f8c14");

    /** Pinned against an independent UUIDv5 implementation: a change here renames every build ever queued. */
    @Test
    void theNamespaceAndDerivationsArePinned() {
        UUID buildId = BuildIds.buildId(PUSH_EVENT_ID);

        assertThat(buildId).isEqualTo(UUID.fromString("8de986ff-c85b-5831-9ec1-32d4c9c7af79"));
        assertThat(BuildIds.queuedEventId(buildId)).isEqualTo(UUID.fromString("b723fcc0-ed99-5553-a4ae-85ddd6414bc0"));
        assertThat(BuildIds.dispatchedEventId(buildId, 1))
                .isEqualTo(UUID.fromString("7edd4a74-98d3-5ffd-b8db-a98604c86ba9"));
        assertThat(BuildIds.cancelledEventId(buildId))
                .isEqualTo(UUID.fromString("86e32d92-5553-5d60-b55f-c3163aedbd65"));
    }

    @Test
    void theSamePushAlwaysNamesTheSameBuild() {
        assertThat(BuildIds.buildId(PUSH_EVENT_ID)).isEqualTo(BuildIds.buildId(PUSH_EVENT_ID));
        assertThat(BuildIds.buildId(UUID.randomUUID())).isNotEqualTo(BuildIds.buildId(PUSH_EVENT_ID));
    }

    @Test
    void everyDiscriminatorAndAttemptNamesADistinctEvent() {
        UUID buildId = BuildIds.buildId(PUSH_EVENT_ID);

        Set<UUID> ids = Set.of(
                buildId,
                BuildIds.queuedEventId(buildId),
                BuildIds.dispatchedEventId(buildId, 1),
                BuildIds.dispatchedEventId(buildId, 2),
                BuildIds.cancelledEventId(buildId));

        assertThat(ids).hasSize(5);
    }

    @Test
    void derivedIdsAreVersionFiveRfcVariant() {
        UUID buildId = BuildIds.buildId(PUSH_EVENT_ID);

        assertThat(buildId.version()).isEqualTo(5);
        assertThat(buildId.variant()).isEqualTo(2);
        assertThat(BuildIds.cancelledEventId(buildId).version()).isEqualTo(5);
    }
}
