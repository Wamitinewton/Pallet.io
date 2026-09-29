package io.pallet.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.events.NotificationRequested;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.events.PlatformEvent;
import io.pallet.common.test.annotations.UnitTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@UnitTest
class OutboxEventTypesTest {

    @Test
    void resolvesEachDeclaredTypeToItsClass() {
        OutboxEventTypes types = OutboxEventTypes.of(OrgMemberAdded.class, NotificationRequested.class);

        assertThat(types.classFor(OrgMemberAdded.TYPE)).isEqualTo(OrgMemberAdded.class);
        assertThat(types.classFor(NotificationRequested.TYPE)).isEqualTo(NotificationRequested.class);
        assertThat(types.publishedTypes()).containsExactlyInAnyOrder(OrgMemberAdded.class, NotificationRequested.class);
    }

    @Test
    void anUndeclaredTypeIsRejected() {
        assertThatThrownBy(() -> OutboxEventTypes.of(OrgMemberAdded.class).classFor("bogus.event"))
                .isInstanceOf(UnknownEventTypeException.class)
                .hasMessageContaining("bogus.event");
    }

    @Test
    void aClassWithoutATypeConstantFailsAtStartup() {
        assertThatThrownBy(() -> OutboxEventTypes.of(Untyped.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(Untyped.class.getName());
    }

    @Test
    void twoClassesDeclaringOneTypeFailAtStartup() {
        assertThatThrownBy(() -> OutboxEventTypes.of(OrgMemberAdded.class, Impostor.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(OrgMemberAdded.TYPE);
    }

    @Test
    void noneDeclaresNothing() {
        assertThat(OutboxEventTypes.none().publishedTypes()).isEmpty();
    }

    record Untyped(UUID eventId, String eventType, String orgId, Instant occurredAt) implements PlatformEvent {}

    record Impostor(UUID eventId, String eventType, String orgId, Instant occurredAt) implements PlatformEvent {

        public static final String TYPE = OrgMemberAdded.TYPE;
    }
}
