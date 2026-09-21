package io.pallet.orgteam.security;

import io.pallet.orgteam.member.Membership;
import io.pallet.orgteam.member.MembershipStatus;
import io.pallet.orgteam.member.Role;
import io.pallet.orgteam.security.AccessExceptions.InsufficientRoleException;
import io.pallet.orgteam.security.AccessExceptions.InvalidRoleTransitionException;
import io.pallet.orgteam.security.AccessExceptions.LastOwnerException;

public class MembershipPolicy {

    public void checkCanViewRemoved(Role actor) {
        if (!actor.atLeast(Role.ADMIN)) {
            throw new InsufficientRoleException();
        }
    }

    public void checkCanInvite(Role actor, Role invited) {
        if (!actor.atLeast(Role.ADMIN)) {
            throw new InsufficientRoleException();
        }
        if (invited == Role.OWNER) {
            throw new InvalidRoleTransitionException();
        }
        if (actor == Role.ADMIN && invited == Role.ADMIN) {
            throw new InsufficientRoleException();
        }
    }

    public void checkCanChangeRole(Role actor, Membership target, Role newRole) {
        if (actor != Role.OWNER) {
            throw new InsufficientRoleException();
        }
        if (target.getRole() == Role.OWNER || newRole == Role.OWNER) {
            throw new InvalidRoleTransitionException();
        }
    }

    public void checkCanRemove(Role actor, boolean self, Membership target) {
        if (target.getRole() == Role.OWNER) {
            throw new LastOwnerException();
        }
        if (self || actor == Role.OWNER) {
            return;
        }
        if (actor == Role.ADMIN && !target.getRole().atLeast(Role.ADMIN)) {
            return;
        }
        throw new InsufficientRoleException();
    }

    public void checkCanTransferOwnership(Role actor, Membership target) {
        if (actor != Role.OWNER) {
            throw new InsufficientRoleException();
        }
        if (target.getStatus() != MembershipStatus.ACTIVE || target.getRole() == Role.OWNER) {
            throw new InvalidRoleTransitionException();
        }
    }
}
