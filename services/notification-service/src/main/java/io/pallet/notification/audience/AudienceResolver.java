package io.pallet.notification.audience;

import io.pallet.notification.domain.Audience;
import java.util.List;

public interface AudienceResolver {

    List<Recipient> resolve(String orgId, Audience audience, String singleRecipient);
}
