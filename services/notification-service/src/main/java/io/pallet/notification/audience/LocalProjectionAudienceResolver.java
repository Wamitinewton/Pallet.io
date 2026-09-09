package io.pallet.notification.audience;

import io.pallet.notification.domain.Audience;
import java.util.List;

class LocalProjectionAudienceResolver implements AudienceResolver {

    private final OrgMembershipRepository orgMembershipRepository;

    LocalProjectionAudienceResolver(OrgMembershipRepository orgMembershipRepository) {
        this.orgMembershipRepository = orgMembershipRepository;
    }

    @Override
    public List<Recipient> resolve(String orgId, Audience audience, String singleRecipient) {
        if (audience == Audience.SINGLE) {
            return singleRecipient == null ? List.of() : List.of(new Recipient(singleRecipient, singleRecipient));
        }
        return orgMembershipRepository.findByOrgIdAndStatus(orgId, OrgMemberStatus.ACTIVE).stream()
                .map(member -> new Recipient(member.getUserId(), member.getEmail()))
                .toList();
    }
}
