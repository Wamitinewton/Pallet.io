package io.pallet.gitintegration.github;

import io.pallet.gitintegration.github.dto.HookDelivery;
import java.util.List;

/** One page of the app's delivery log, newest first; {@code nextCursor} is null on the last page. */
public record HookDeliveryPage(List<HookDelivery> deliveries, String nextCursor) {

    public HookDeliveryPage {
        deliveries = List.copyOf(deliveries);
    }
}
