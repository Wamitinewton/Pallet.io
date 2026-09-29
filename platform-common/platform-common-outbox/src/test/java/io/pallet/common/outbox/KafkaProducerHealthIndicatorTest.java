package io.pallet.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.pallet.common.events.NotificationRequested;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.messaging.MessagingProperties;
import io.pallet.common.test.annotations.UnitTest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.PartitionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.kafka.core.KafkaTemplate;

@UnitTest
class KafkaProducerHealthIndicatorTest {

    private static final Duration TIMEOUT = Duration.ofMillis(200);
    private static final List<PartitionInfo> TWO_PARTITIONS = List.of(
            new PartitionInfo(OrgMemberAdded.TYPE, 0, null, null, null),
            new PartitionInfo(OrgMemberAdded.TYPE, 1, null, null, null));

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);

    private final CountDownLatch release = new CountDownLatch(1);
    private KafkaProducerHealthIndicator indicator;

    @AfterEach
    void close() {
        release.countDown();
        if (indicator != null) {
            indicator.close();
        }
    }

    @Test
    void aServiceThatPublishesNothingIsUpWithoutProbing() {
        indicator = indicator(OutboxEventTypes.none());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("metadata", "no published types");
        verifyNoInteractions(template);
    }

    @Test
    void probesTheFirstPublishedTopicOnceAndThenStaysUp() {
        when(template.partitionsFor(NotificationRequested.TYPE)).thenReturn(TWO_PARTITIONS);
        indicator = indicator(OutboxEventTypes.of(OrgMemberAdded.class, NotificationRequested.class));

        Health first = indicator.health();
        Health second = indicator.health();

        assertThat(first.getStatus()).isEqualTo(Status.UP);
        assertThat(first.getDetails()).containsEntry("partitions", 2);
        assertThat(second.getStatus()).isEqualTo(Status.UP);
        assertThat(second.getDetails()).containsEntry("metadata", "obtained");
        verify(template, times(1)).partitionsFor(anyString());
    }

    @Test
    void aFailedProbeIsDownWithTheBrokerErrorAndTheNextCallProbesAgain() {
        when(template.partitionsFor(OrgMemberAdded.TYPE))
                .thenThrow(new KafkaException("broker unavailable"))
                .thenReturn(TWO_PARTITIONS);
        indicator = indicator(OutboxEventTypes.of(OrgMemberAdded.class));

        Health failed = indicator.health();
        Health recovered = indicator.health();

        assertThat(failed.getStatus()).isEqualTo(Status.DOWN);
        assertThat(failed.getDetails()).containsEntry("reason", "KafkaException");
        assertThat(recovered.getStatus()).isEqualTo(Status.UP);
        verify(template, times(2)).partitionsFor(OrgMemberAdded.TYPE);
    }

    @Test
    void aSlowBrokerTimesOutWithoutStackingProbesAndIsUpOnceMetadataArrives() {
        when(template.partitionsFor(OrgMemberAdded.TYPE)).thenAnswer(invocation -> {
            release.await();
            return TWO_PARTITIONS;
        });
        indicator = indicator(OutboxEventTypes.of(OrgMemberAdded.class));

        for (int call = 0; call < 3; call++) {
            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("reason", "metadata timeout");
        }
        verify(template, times(1)).partitionsFor(OrgMemberAdded.TYPE);

        release.countDown();

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        verify(template, times(1)).partitionsFor(OrgMemberAdded.TYPE);
    }

    private KafkaProducerHealthIndicator indicator(OutboxEventTypes eventTypes) {
        return new KafkaProducerHealthIndicator(template, eventTypes, messaging());
    }

    private static MessagingProperties messaging() {
        return new MessagingProperties(
                3,
                new MessagingProperties.Retry(4, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30)),
                new MessagingProperties.Publish(TIMEOUT),
                true,
                (short) 1,
                3,
                new MessagingProperties.DltMonitor(true),
                new MessagingProperties.Compaction(Duration.ofHours(1)));
    }
}
