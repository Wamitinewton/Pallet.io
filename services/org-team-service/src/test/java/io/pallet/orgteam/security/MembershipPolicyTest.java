package io.pallet.orgteam.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.error.AppException;
import io.pallet.common.test.annotations.UnitTest;
import io.pallet.orgteam.member.Membership;
import io.pallet.orgteam.member.MembershipStatus;
import io.pallet.orgteam.member.Role;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

@UnitTest
class MembershipPolicyTest {

    private static final String ALLOW = "ALLOW";
    private static final String INSUFFICIENT_ROLE = "INSUFFICIENT_ROLE";
    private static final String INVALID_ROLE_TRANSITION = "INVALID_ROLE_TRANSITION";

    private final MembershipPolicy policy = new MembershipPolicy();

    @ParameterizedTest(name = "{0} invites {1} -> {2}")
    @CsvSource({
        "OWNER,   OWNER,     INVALID_ROLE_TRANSITION",
        "OWNER,   ADMIN,     ALLOW",
        "OWNER,   DEVELOPER, ALLOW",
        "OWNER,   VIEWER,    ALLOW",
        "ADMIN,   OWNER,     INVALID_ROLE_TRANSITION",
        "ADMIN,   ADMIN,     INSUFFICIENT_ROLE",
        "ADMIN,   DEVELOPER, ALLOW",
        "ADMIN,   VIEWER,    ALLOW",
        "DEVELOPER, OWNER,   INSUFFICIENT_ROLE",
        "DEVELOPER, ADMIN,   INSUFFICIENT_ROLE",
        "DEVELOPER, DEVELOPER, INSUFFICIENT_ROLE",
        "DEVELOPER, VIEWER,  INSUFFICIENT_ROLE",
        "VIEWER,  OWNER,     INSUFFICIENT_ROLE",
        "VIEWER,  ADMIN,     INSUFFICIENT_ROLE",
        "VIEWER,  DEVELOPER, INSUFFICIENT_ROLE",
        "VIEWER,  VIEWER,    INSUFFICIENT_ROLE"
    })
    void invite(Role actor, Role invited, String expected) {
        assertOutcome(expected, () -> policy.checkCanInvite(actor, invited));
    }

    @ParameterizedTest(name = "{0} changes {1} to {2}")
    @CsvSource({
        "OWNER, OWNER,     ADMIN,     INVALID_ROLE_TRANSITION",
        "OWNER, OWNER,     OWNER,     INVALID_ROLE_TRANSITION",
        "OWNER, ADMIN,     OWNER,     INVALID_ROLE_TRANSITION",
        "OWNER, DEVELOPER, OWNER,     INVALID_ROLE_TRANSITION",
        "OWNER, VIEWER,    OWNER,     INVALID_ROLE_TRANSITION",
        "OWNER, ADMIN,     ADMIN,     ALLOW",
        "OWNER, ADMIN,     DEVELOPER, ALLOW",
        "OWNER, ADMIN,     VIEWER,    ALLOW",
        "OWNER, DEVELOPER, ADMIN,     ALLOW",
        "OWNER, VIEWER,    DEVELOPER, ALLOW",
        "ADMIN, DEVELOPER, VIEWER,    INSUFFICIENT_ROLE",
        "ADMIN, ADMIN,     VIEWER,    INSUFFICIENT_ROLE",
        "ADMIN, OWNER,     ADMIN,     INSUFFICIENT_ROLE",
        "ADMIN, VIEWER,    OWNER,     INSUFFICIENT_ROLE",
        "DEVELOPER, VIEWER, ADMIN,    INSUFFICIENT_ROLE",
        "VIEWER, VIEWER,   DEVELOPER, INSUFFICIENT_ROLE"
    })
    void changeRole(Role actor, Role targetRole, Role newRole, String expected) {
        assertOutcome(expected, () -> policy.checkCanChangeRole(actor, member(targetRole), newRole));
    }

    @ParameterizedTest(name = "non-owner {0} can never change any role")
    @EnumSource(
            value = Role.class,
            names = {"VIEWER", "DEVELOPER", "ADMIN"})
    void onlyTheOwnerChangesRoles(Role actor) {
        for (Role target : Role.values()) {
            for (Role newRole : Role.values()) {
                assertOutcome(INSUFFICIENT_ROLE, () -> policy.checkCanChangeRole(actor, member(target), newRole));
            }
        }
    }

    @ParameterizedTest(name = "{0} removes {2} (self={1}) -> {3}")
    @CsvSource({
        "OWNER,     false, OWNER,     LAST_OWNER",
        "OWNER,     true,  OWNER,     LAST_OWNER",
        "ADMIN,     false, OWNER,     LAST_OWNER",
        "DEVELOPER, false, OWNER,     LAST_OWNER",
        "VIEWER,    false, OWNER,     LAST_OWNER",
        "OWNER,     false, ADMIN,     ALLOW",
        "OWNER,     false, DEVELOPER, ALLOW",
        "OWNER,     false, VIEWER,    ALLOW",
        "ADMIN,     false, ADMIN,     INSUFFICIENT_ROLE",
        "ADMIN,     false, DEVELOPER, ALLOW",
        "ADMIN,     false, VIEWER,    ALLOW",
        "DEVELOPER, false, ADMIN,     INSUFFICIENT_ROLE",
        "DEVELOPER, false, DEVELOPER, INSUFFICIENT_ROLE",
        "DEVELOPER, false, VIEWER,    INSUFFICIENT_ROLE",
        "VIEWER,    false, ADMIN,     INSUFFICIENT_ROLE",
        "VIEWER,    false, DEVELOPER, INSUFFICIENT_ROLE",
        "VIEWER,    false, VIEWER,    INSUFFICIENT_ROLE",
        "ADMIN,     true,  ADMIN,     ALLOW",
        "DEVELOPER, true,  DEVELOPER, ALLOW",
        "VIEWER,    true,  VIEWER,    ALLOW"
    })
    void remove(Role actor, boolean self, Role targetRole, String expected) {
        assertOutcome(expected, () -> policy.checkCanRemove(actor, self, member(targetRole)));
    }

    @ParameterizedTest(name = "{0} transfers to {1}")
    @CsvSource({
        "OWNER,     ADMIN,     ALLOW",
        "OWNER,     DEVELOPER, ALLOW",
        "OWNER,     VIEWER,    ALLOW",
        "OWNER,     OWNER,     INVALID_ROLE_TRANSITION",
        "ADMIN,     ADMIN,     INSUFFICIENT_ROLE",
        "ADMIN,     VIEWER,    INSUFFICIENT_ROLE",
        "DEVELOPER, ADMIN,     INSUFFICIENT_ROLE",
        "VIEWER,    ADMIN,     INSUFFICIENT_ROLE"
    })
    void transferOwnership(Role actor, Role targetRole, String expected) {
        assertOutcome(expected, () -> policy.checkCanTransferOwnership(actor, member(targetRole)));
    }

    @Test
    void transferToARemovedMemberIsRejected() {
        Membership removed = removedMember(Role.ADMIN);

        assertOutcome(INVALID_ROLE_TRANSITION, () -> policy.checkCanTransferOwnership(Role.OWNER, removed));
    }

    private static void assertOutcome(String expected, Runnable check) {
        if (ALLOW.equals(expected)) {
            assertThatCode(check::run).doesNotThrowAnyException();
            return;
        }
        assertThatThrownBy(check::run)
                .isInstanceOf(AppException.class)
                .extracting(thrown -> ((AppException) thrown).getErrorCode())
                .isEqualTo(expected);
    }

    private static Membership member(Role role) {
        return new Membership("org-1", "user-" + role, role + "@example.com", role.name(), role, Instant.EPOCH);
    }

    private static Membership removedMember(Role role) {
        Membership member = member(role);
        ReflectionTestUtils.setField(member, "status", MembershipStatus.REMOVED);
        return member;
    }
}
