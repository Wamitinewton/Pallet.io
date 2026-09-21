package io.pallet.orgteam.member;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.UnitTest;
import org.junit.jupiter.api.Test;

@UnitTest
class MemberFilterTest {

    @Test
    void defaultsToActiveMembersOfEveryRole() {
        MemberFilter filter = new MemberFilter(null, null, null);

        assertThat(filter.status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(filter.roles()).containsExactlyInAnyOrder(Role.values());
        assertThat(filter.prefixPattern()).isEqualTo("%");
    }

    @Test
    void aRoleNarrowsTheRoleSet() {
        assertThat(new MemberFilter(null, Role.ADMIN, null).roles()).containsExactly(Role.ADMIN);
    }

    @Test
    void thePrefixIsLowercasedStrippedAndEscaped() {
        assertThat(new MemberFilter(null, null, "  Ali_ce%!  ").prefixPattern()).isEqualTo("ali!_ce!%!!%");
        assertThat(new MemberFilter(null, null, "   ").prefixPattern()).isEqualTo("%");
    }
}
