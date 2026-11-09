package io.pallet.build.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.annotations.UnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class RoleTest {

    @Test
    void eachRoleMeetsItsOwnFloorAndEveryLowerOne() {
        assertThat(Role.OWNER.atLeast(Role.OWNER)).isTrue();
        assertThat(Role.OWNER.atLeast(Role.VIEWER)).isTrue();
        assertThat(Role.ADMIN.atLeast(Role.DEVELOPER)).isTrue();
        assertThat(Role.DEVELOPER.atLeast(Role.DEVELOPER)).isTrue();
        assertThat(Role.DEVELOPER.atLeast(Role.VIEWER)).isTrue();
        assertThat(Role.VIEWER.atLeast(Role.VIEWER)).isTrue();
    }

    @Test
    void noRoleMeetsAHigherFloor() {
        assertThat(Role.ADMIN.atLeast(Role.OWNER)).isFalse();
        assertThat(Role.DEVELOPER.atLeast(Role.ADMIN)).isFalse();
        assertThat(Role.VIEWER.atLeast(Role.DEVELOPER)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void theReadModelsUpperCaseNameAndTheTopicsLowerCaseNameEachParse(Role role) {
        assertThat(Role.fromReadModel(role.name())).isEqualTo(role);
        assertThat(Role.fromWireName(role.wireName())).contains(role);
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "Owner", " OWNER", "SUPERUSER", ""})
    void anUnknownReadModelValueIsNeverMappedToSomethingClose(String value) {
        assertThatThrownBy(() -> Role.fromReadModel(value)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "Owner", " owner", "superuser", ""})
    void anUnknownWireNameIsEmpty(String value) {
        assertThat(Role.fromWireName(value)).isEmpty();
    }

    @Test
    void aMissingValueIsRefused() {
        assertThatThrownBy(() -> Role.fromReadModel(null)).isInstanceOf(IllegalStateException.class);
    }
}
