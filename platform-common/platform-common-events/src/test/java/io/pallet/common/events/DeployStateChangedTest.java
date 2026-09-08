package io.pallet.common.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeployStateChangedTest {

    @Test
    void factoryStampsEnvelopeFields() {
        DeployStateChanged event = DeployStateChanged.of("org_9k2j7f", "dep_4f8a21", "BUILDING", "ROUTING");

        assertThat(event.eventId()).isNotNull();
        assertThat(event.eventType()).isEqualTo("deploy.state.changed");
        assertThat(event.orgId()).isEqualTo("org_9k2j7f");
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.fromState()).isEqualTo("BUILDING");
        assertThat(event.toState()).isEqualTo("ROUTING");
    }
}
