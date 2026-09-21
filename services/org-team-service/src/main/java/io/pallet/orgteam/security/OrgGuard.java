package io.pallet.orgteam.security;

import io.pallet.orgteam.member.Membership;
import io.pallet.orgteam.member.MembershipRepository;
import io.pallet.orgteam.member.MembershipStatus;
import io.pallet.orgteam.member.Role;
import io.pallet.orgteam.org.OrgStatus;
import io.pallet.orgteam.org.Organization;
import io.pallet.orgteam.org.OrganizationRepository;
import io.pallet.orgteam.security.AccessExceptions.InsufficientRoleException;
import io.pallet.orgteam.security.AccessExceptions.NotAMemberException;
import io.pallet.orgteam.security.AccessExceptions.OrgNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Entry checks shared by every mutating service: lock the organization, then vet the acting member. */
@Component
public class OrgGuard {

    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;

    public OrgGuard(OrganizationRepository organizations, MembershipRepository memberships) {
        this.organizations = organizations;
        this.memberships = memberships;
    }

    /** Takes the organization's row lock, serializing writers per tenant. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Organization lockActive(String orgId) {
        Organization organization = organizations.lockById(orgId).orElseThrow(OrgNotFoundException::new);
        if (organization.getStatus() != OrgStatus.ACTIVE) {
            throw new OrgNotFoundException();
        }
        return organization;
    }

    public Membership activeActor(String orgId, String userId) {
        return memberships
                .findByOrgIdAndUserIdAndStatus(orgId, userId, MembershipStatus.ACTIVE)
                .orElseThrow(NotAMemberException::new);
    }

    /** Locks the organization and returns the actor, who must hold at least {@code minimum}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Membership lockAsAtLeast(String orgId, String actorUserId, Role minimum) {
        lockActive(orgId);
        Membership actor = activeActor(orgId, actorUserId);
        if (!actor.getRole().atLeast(minimum)) {
            throw new InsufficientRoleException();
        }
        return actor;
    }
}
