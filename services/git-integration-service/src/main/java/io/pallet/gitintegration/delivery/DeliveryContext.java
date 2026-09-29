package io.pallet.gitintegration.delivery;

import io.pallet.gitintegration.delivery.NeedsGitHub.Lookup;
import io.pallet.gitintegration.delivery.payload.DeliveryPayload;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** What a handler sees of one delivery: the parsed payload, and the answers to GitHub lookups from earlier rounds. */
public record DeliveryContext(
        UUID deliveryId, String event, String action, Long installationId, DeliveryPayload payload, Lookups lookups) {

    public <T extends DeliveryPayload> T payload(Class<T> type) {
        return type.cast(payload);
    }

    /** Answers gathered across the rounds of one delivery; nothing carries over to another delivery. */
    public static final class Lookups {

        public static final Lookups NONE = new Lookups(Map.of());

        private final Map<Lookup<?>, Object> answers;

        private Lookups(Map<Lookup<?>, Object> answers) {
            this.answers = Map.copyOf(answers);
        }

        @SuppressWarnings("unchecked")
        public <T> Optional<T> answer(Lookup<T> lookup) {
            return Optional.ofNullable((T) answers.get(lookup));
        }

        public boolean isAnswered(Lookup<?> lookup) {
            return answers.containsKey(lookup);
        }

        public int size() {
            return answers.size();
        }

        Lookups with(Map<Lookup<?>, Object> more) {
            Map<Lookup<?>, Object> merged = new HashMap<>(answers);
            merged.putAll(more);
            return new Lookups(merged);
        }
    }
}
