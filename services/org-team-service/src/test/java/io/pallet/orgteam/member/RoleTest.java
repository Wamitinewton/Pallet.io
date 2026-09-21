package io.pallet.orgteam.member;

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
    void ranksAscendFromViewerToOwner() {
        assertThat(Role.values()).containsExactly(Role.VIEWER, Role.DEVELOPER, Role.ADMIN, Role.OWNER);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void atLeastFollowsRank(Role role) {
        for (Role other : Role.values()) {
            assertThat(role.atLeast(other)).isEqualTo(role.ordinal() >= other.ordinal());
        }
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void keycloakNameRoundTrips(Role role) {
        assertThat(role.keycloakName()).isEqualTo(role.name().toLowerCase());
        assertThat(Role.fromKeycloakName(role.keycloakName())).isEqualTo(role);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "Owner", "superuser", "offline_access", ""})
    void anythingButTheFourLowercaseNamesIsRejected(String name) {
        assertThatThrownBy(() -> Role.fromKeycloakName(name)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullIsRejected() {
        assertThatThrownBy(() -> Role.fromKeycloakName(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
