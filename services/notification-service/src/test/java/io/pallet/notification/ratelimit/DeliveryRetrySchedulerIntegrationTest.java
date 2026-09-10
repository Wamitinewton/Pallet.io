package io.pallet.notification.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.common.test.annotations.RepositoryTest;
import io.pallet.notification.channel.ChannelRouter;
import io.pallet.notification.channel.NotificationChannel;
import io.pallet.notification.config.NotificationServiceProperties;
import io.pallet.notification.domain.Audience;
import io.pallet.notification.domain.Channel;
import io.pallet.notification.domain.DeliveryStatus;
import io.pallet.notification.domain.Notification;
import io.pallet.notification.domain.NotificationDelivery;
import io.pallet.notification.repository.NotificationDeliveryRepository;
import io.pallet.notification.repository.NotificationRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@RepositoryTest
class DeliveryRetrySchedulerIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    private NotificationChannel emailChannel;
    private OrgRateLimiter orgRateLimiter;
    private ChannelRouter channelRouter;

    @BeforeEach
    void setUp() {
        emailChannel = mock(NotificationChannel.class);
        when(emailChannel.type()).thenReturn(Channel.EMAIL);
        orgRateLimiter = mock(OrgRateLimiter.class);
        when(orgRateLimiter.tryAcquire(any())).thenReturn(true);
        channelRouter =
                new ChannelRouter(List.of(emailChannel), deliveryRepository, orgRateLimiter, new SimpleMeterRegistry());
    }

    private Notification persistNotification() {
        Notification notification = new Notification(
                "org-1",
                "welcome-email",
                UUID.randomUUID(),
                null,
                Audience.SINGLE,
                "Welcome!",
                "<p>Welcome aboard.</p>",
                Map.of());
        return notificationRepository.saveAndFlush(notification);
    }

    private NotificationDelivery persistThrottledDelivery(UUID notificationId, String recipient) {
        NotificationDelivery delivery = new NotificationDelivery(notificationId, Channel.EMAIL, recipient);
        delivery.markThrottled();
        return deliveryRepository.saveAndFlush(delivery);
    }

    private DeliveryRetryScheduler schedulerWithBatchLimit(int batchLimit) {
        NotificationServiceProperties properties = new NotificationServiceProperties(
                new NotificationServiceProperties.Email("no-reply@pallet.local"),
                new NotificationServiceProperties.RateLimit(
                        100, Duration.ofMinutes(1), Duration.ofSeconds(30), batchLimit));
        return new DeliveryRetryScheduler(
                deliveryRepository, notificationRepository, channelRouter, properties, new SimpleMeterRegistry());
    }

    @Test
    void sweepResendsAThrottledRowThroughTheNormalSendPath() throws Exception {
        Notification notification = persistNotification();
        NotificationDelivery delivery = persistThrottledDelivery(notification.getId(), "user1@example.com");

        schedulerWithBatchLimit(500).sweep();

        NotificationDelivery reloaded =
                deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DeliveryStatus.SENT);
        verify(emailChannel, times(1)).deliver(any(), eq("user1@example.com"));
    }

    @Test
    void aStillRejectedPermitLeavesTheDeliveryThrottledRatherThanAnyOtherState() throws Exception {
        Notification notification = persistNotification();
        NotificationDelivery delivery = persistThrottledDelivery(notification.getId(), "user1@example.com");
        when(orgRateLimiter.tryAcquire(any())).thenReturn(false);

        schedulerWithBatchLimit(500).sweep();

        NotificationDelivery reloaded =
                deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DeliveryStatus.THROTTLED);
        verify(emailChannel, never()).deliver(any(), any());
    }

    @Test
    void oneSweepOnlyProcessesTheConfiguredBatchLimit() throws Exception {
        Notification notification = persistNotification();
        for (int i = 0; i < 5; i++) {
            persistThrottledDelivery(notification.getId(), "user" + i + "@example.com");
        }

        schedulerWithBatchLimit(2).sweep();

        long stillThrottled = deliveryRepository.findAll().stream()
                .filter(d -> d.getStatus() == DeliveryStatus.THROTTLED)
                .count();
        assertThat(stillThrottled).isEqualTo(3);
        verify(emailChannel, times(2)).deliver(any(), any());
    }
}
