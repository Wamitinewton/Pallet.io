package io.pallet.notification.audience;

import io.pallet.notification.domain.Audience;
import java.util.List;

/**
 * The broadcast-disabled fallback: never reads {@code org_members}, so {@code ORG} always
 * resolves to zero recipients regardless of what membership data exists. Gated behind
 * {@code pallet.notification.audience.local-projection=false} for a deployment that wants to
 * hard-disable broadcast; {@link LocalProjectionAudienceResolver} is the active default.
 */
class SingleAudienceResolver implements AudienceResolver {

    @Override
    public List<Recipient> resolve(String orgId, Audience audience, String singleRecipient) {
        if (audience == Audience.ORG) {
            return List.of();
        }
        return singleRecipient == null ? List.of() : List.of(new Recipient(singleRecipient, singleRecipient));
    }
}
