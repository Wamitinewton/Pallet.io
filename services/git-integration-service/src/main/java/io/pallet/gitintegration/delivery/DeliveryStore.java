package io.pallet.gitintegration.delivery;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Makes a verified delivery durable. The primary key absorbs GitHub's redeliveries and any duplicate POST. */
@Component
public class DeliveryStore {

    public static final String UNSUBSCRIBED_EVENT = "UNSUBSCRIBED_EVENT";

    private final DeliveryRepository deliveries;

    DeliveryStore(DeliveryRepository deliveries) {
        this.deliveries = deliveries;
    }

    /** @return {@code true} if this call stored the delivery, {@code false} if it was already stored */
    @Transactional
    public boolean store(NewDelivery delivery) {
        boolean subscribed = SubscribedEvents.contains(delivery.event());
        return deliveries.insertIfAbsent(
                        delivery.deliveryId(),
                        delivery.event(),
                        delivery.action(),
                        delivery.installationId(),
                        delivery.payload(),
                        (subscribed ? DeliveryStatus.RECEIVED : DeliveryStatus.IGNORED).name(),
                        subscribed ? null : UNSUBSCRIBED_EVENT,
                        delivery.traceparent())
                == 1;
    }

    /** {@code payload} must already be JSON Postgres accepts as {@code jsonb}. */
    public record NewDelivery(
            UUID deliveryId, String event, String action, Long installationId, String payload, String traceparent) {}
}
