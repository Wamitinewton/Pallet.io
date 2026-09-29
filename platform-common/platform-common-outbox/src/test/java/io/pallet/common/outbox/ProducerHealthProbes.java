package io.pallet.common.outbox;

import io.pallet.common.messaging.MessagingProperties;
import java.time.Duration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.kafka.core.KafkaTemplate;

public final class ProducerHealthProbes {

    public interface Probe extends AutoCloseable {

        Health health();

        @Override
        void close();
    }

    private ProducerHealthProbes() {}

    public static Probe create(KafkaTemplate<String, Object> template, OutboxEventTypes eventTypes, Duration timeout) {
        MessagingProperties messaging = new MessagingProperties(
                3,
                new MessagingProperties.Retry(4, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30)),
                new MessagingProperties.Publish(timeout),
                true,
                (short) 1,
                3,
                new MessagingProperties.DltMonitor(true),
                new MessagingProperties.Compaction(Duration.ofHours(1)));
        KafkaProducerHealthIndicator indicator = new KafkaProducerHealthIndicator(template, eventTypes, messaging);
        return new Probe() {
            @Override
            public Health health() {
                return indicator.health();
            }

            @Override
            public void close() {
                indicator.close();
            }
        };
    }
}
