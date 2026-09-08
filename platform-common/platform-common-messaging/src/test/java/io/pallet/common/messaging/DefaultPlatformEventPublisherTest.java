package io.pallet.common.messaging;

import io.micrometer.core.instrument.search.RequiredSearch;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.common.error.ExternalServiceException;
import io.pallet.common.events.DeployStateChanged;
import io.pallet.common.events.EventHeaders;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultPlatformEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private DefaultPlatformEventPublisher publisher;

    private static MessagingProperties properties(Duration sendTimeout) {
        return new MessagingProperties(
            3,
            new MessagingProperties.Retry(4, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30)),
            new MessagingProperties.Publish(sendTimeout),
            true, (short) 1, 3,
            new MessagingProperties.DltMonitor(true));
    }

    private static String header(ProducerRecord<String, Object> record, String key) {
        var header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<SendResult<String, Object>> ackedFuture() {
        return CompletableFuture.completedFuture(mock(SendResult.class));
    }

    @BeforeEach
    void setUp() {
        publisher = new DefaultPlatformEventPublisher(template, meterRegistry, properties(Duration.ofMillis(200)));
    }

    @Test
    void resolvesTopicKeyAndHeadersFromTheEnvelope() {
        when(template.send(any(ProducerRecord.class))).thenReturn(ackedFuture());
        DeployStateChanged event = DeployStateChanged.of("org_9k2j7f", "dep_1", "BUILDING", "ROUTING");

        publisher.publish(event);

        ProducerRecord<String, Object> sent = captureSent();
        assertThat(sent.topic()).isEqualTo(event.eventType());
        assertThat(sent.key()).isEqualTo("org_9k2j7f");
        assertThat(sent.value()).isEqualTo(event);
        assertThat(header(sent, EventHeaders.EVENT_ID)).isEqualTo(event.eventId().toString());
        assertThat(header(sent, EventHeaders.EVENT_TYPE)).isEqualTo("deploy.state.changed");
        assertThat(header(sent, EventHeaders.OCCURRED_AT)).isEqualTo(event.occurredAt().toString());
        assertThat(header(sent, EventHeaders.ORG_ID)).isEqualTo("org_9k2j7f");
        assertThat(published("success")).isEqualTo(1.0);
    }

    @Test
    void explicitTopicOverloadKeepsEnvelopeKeyAndHeaders() {
        when(template.send(any(ProducerRecord.class))).thenReturn(ackedFuture());
        DeployStateChanged event = DeployStateChanged.of("org_x", "dep_2", "A", "B");

        publisher.publish("service.local.topic", event);

        ProducerRecord<String, Object> sent = captureSent();
        assertThat(sent.topic()).isEqualTo("service.local.topic");
        assertThat(sent.key()).isEqualTo("org_x");
        assertThat(header(sent, EventHeaders.EVENT_ID)).isEqualTo(event.eventId().toString());
    }

    @Test
    void aFailedSendSurfacesAsExternalServiceExceptionWithTopicAndType() {
        when(template.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        DeployStateChanged event = DeployStateChanged.of("org_9k2j7f", "dep_3", "A", "B");

        assertThatThrownBy(() -> publisher.publish(event))
            .isInstanceOf(ExternalServiceException.class)
            .hasMessageContaining("topic=deploy.state.changed")
            .hasMessageContaining("type=deploy.state.changed")
            .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(published("failure")).isEqualTo(1.0);
    }

    @Test
    void aSendThatNeverCompletesTimesOutAsExternalServiceException() {
        publisher = new DefaultPlatformEventPublisher(template, meterRegistry, properties(Duration.ofMillis(100)));
        when(template.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());
        DeployStateChanged event = DeployStateChanged.of("org_9k2j7f", "dep_4", "A", "B");

        assertThatThrownBy(() -> publisher.publish(event))
            .isInstanceOf(ExternalServiceException.class)
            .hasMessageContaining("topic=deploy.state.changed");
        assertThat(published("failure")).isEqualTo(1.0);
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, Object> captureSent() {
        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        org.mockito.Mockito.verify(template).send(captor.capture());
        return captor.getValue();
    }

    private double published(String outcome) {
        RequiredSearch search = meterRegistry.get("messaging.published").tags("outcome", outcome);
        return search.counter().count();
    }
}
