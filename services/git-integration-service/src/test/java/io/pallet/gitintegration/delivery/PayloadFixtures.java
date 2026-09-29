package io.pallet.gitintegration.delivery;

import io.pallet.gitintegration.delivery.payload.PushPayload;
import io.pallet.gitintegration.support.WebhookFixtures;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Recorded push fixtures run through the real parser, with the default skip markers. */
public final class PayloadFixtures {

    public static final List<String> SKIP_MARKERS = List.of("[skip ci]", "[ci skip]", "[pallet skip]");

    private static final PayloadParser PARSER = new PayloadParser(8L * 1024 * 1024, SKIP_MARKERS);

    private PayloadFixtures() {}

    public static PushPayload push(String fixture, Map<String, Object> overrides) {
        WebhookFixtures.Delivery delivery = WebhookFixtures.delivery(fixture);
        overrides.forEach(delivery::with);
        return (PushPayload) PARSER.parse(SubscribedEvents.PUSH, new String(delivery.body(), StandardCharsets.UTF_8));
    }
}
