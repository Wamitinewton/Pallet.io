package io.pallet.gitintegration.push;

import io.pallet.gitintegration.delivery.DeliveryContext;
import io.pallet.gitintegration.delivery.DeliveryHandler;
import io.pallet.gitintegration.delivery.DeliveryOutcome;
import io.pallet.gitintegration.delivery.SubscribedEvents;
import io.pallet.gitintegration.delivery.payload.PushPayload;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
class PushHandler implements DeliveryHandler {

    private final PushProcessor processor;

    PushHandler(PushProcessor processor) {
        this.processor = processor;
    }

    @Override
    public Set<String> events() {
        return Set.of(SubscribedEvents.PUSH);
    }

    @Override
    public DeliveryOutcome handle(DeliveryContext context) {
        return processor.process(
                context.deliveryId(), context.receivedAt(), context.payload(PushPayload.class), context.lookups());
    }
}
