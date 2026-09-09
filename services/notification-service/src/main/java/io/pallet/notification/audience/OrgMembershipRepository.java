package io.pallet.notification.audience;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The read side of the membership seam described in ARCHITECTURE.md §Broadcast notifications.
 * The write side — an {@code OrgMembershipEventListener} consuming {@code org.member.added} /
 * {@code org.member.removed} — plugs in once org-team-service publishes those topics; until then
 * this table stays empty and an {@code ORG} broadcast resolves to zero recipients.
 */
public interface OrgMembershipRepository extends JpaRepository<OrgMember, OrgMemberId> {

    List<OrgMember> findByOrgIdAndStatus(String orgId, OrgMemberStatus status);
}
