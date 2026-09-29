package io.pallet.gitintegration.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.annotations.UnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class RoleTest {

    @Test
    void eachRoleMeetsItsOwnFloorAndEveryLowerOne() {
        assertThat(Role.OWNER.atLeast(Role.OWNER)).isTrue();
        assertThat(Role.OWNER.atLeast(Role.VIEWER)).isTrue();
        assertThat(Role.ADMIN.atLeast(Role.DEVELOPER)).isTrue();
        assertThat(Role.DEVELOPER.atLeast(Role.VIEWER)).isTrue();
        assertThat(Role.VIEWER.atLeast(Role.VIEWER)).isTrue();
    }

    @Test
    void noRoleMeetsAHigherFloor() {
        assertThat(Role.ADMIN.atLeast(Role.OWNER)).isFalse();
        assertThat(Role.DEVELOPER.atLeast(Role.ADMIN)).isFalse();
        assertThat(Role.VIEWER.atLeast(Role.DEVELOPER)).isFalse();
    }

    @Test
    void theReadModelsLowercaseNamesParse() {
        assertThat(Role.fromReadModel("owner")).isEqualTo(Role.OWNER);
        assertThat(Role.fromReadModel("admin")).isEqualTo(Role.ADMIN);
        assertThat(Role.fromReadModel("developer")).isEqualTo(Role.DEVELOPER);
        assertThat(Role.fromReadModel("viewer")).isEqualTo(Role.VIEWER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "Owner", " owner", "superuser", ""})
    void anythingElseIsNeverMappedToSomethingClose(String value) {
        assertThat(Role.fromWireName(value)).isEmpty();
        assertThatThrownBy(() -> Role.fromReadModel(value)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aMissingValueIsRefused() {
        assertThatThrownBy(() -> Role.fromReadModel(null)).isInstanceOf(IllegalStateException.class);
    }
}
