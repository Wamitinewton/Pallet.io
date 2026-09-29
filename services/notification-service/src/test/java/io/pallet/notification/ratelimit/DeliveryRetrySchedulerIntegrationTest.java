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
import io.pallet.notification.template.TemplateRenderer.RenderedNotification;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@RepositoryTest
class DeliveryRetrySchedulerIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    private static final int SWEEPERS = 4;
    private static final int CONTENDED_ROWS = 40;

    private NotificationChannel emailChannel;
    private OrgRateLimiter orgRateLimiter;
    private ChannelRouter channelRouter;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        emailChannel = mock(NotificationChannel.class);
        when(emailChannel.type()).thenReturn(Channel.EMAIL);
        orgRateLimiter = mock(OrgRateLimiter.class);
        when(orgRateLimiter.tryAcquire(any())).thenReturn(true);
        meterRegistry = new SimpleMeterRegistry();
        channelRouter = new ChannelRouter(List.of(emailChannel), deliveryRepository, orgRateLimiter, meterRegistry);
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
        return scheduler(channelRouter, batchLimit);
    }

    private DeliveryRetryScheduler scheduler(ChannelRouter router, int batchLimit) {
        NotificationServiceProperties properties = new NotificationServiceProperties(
                new NotificationServiceProperties.Email("no-reply@pallet.local"),
                new NotificationServiceProperties.RateLimit(
                        100, Duration.ofMinutes(1), Duration.ofSeconds(30), batchLimit));
        return new DeliveryRetryScheduler(
                deliveryRepository, notificationRepository, router, properties, meterRegistry);
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

    @Test
    void eachClaimedRowCountsOneRetryOnItsChannel() throws Exception {
        Notification notification = persistNotification();
        persistThrottledDelivery(notification.getId(), "user1@example.com");
        persistThrottledDelivery(notification.getId(), "user2@example.com");

        schedulerWithBatchLimit(500).sweep();

        assertThat(meterRegistry
                        .get("notifications.retried")
                        .tag("channel", "EMAIL")
                        .counter()
                        .count())
                .isEqualTo(2.0);
    }

    @Test
    void anEmptySweepCountsNothingAndSendsNothing() throws Exception {
        schedulerWithBatchLimit(500).sweep();

        assertThat(meterRegistry.find("notifications.retried").counter()).isNull();
        verify(emailChannel, never()).deliver(any(), any());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentSweepsDeliverEachThrottledRowExactlyOnce() throws Exception {
        CountingChannel counting = new CountingChannel();
        ChannelRouter router = new ChannelRouter(List.of(counting), deliveryRepository, orgRateLimiter, meterRegistry);
        Notification notification = persistNotification();
        List<String> recipients = new ArrayList<>();
        for (int i = 0; i < CONTENDED_ROWS; i++) {
            String recipient = "contended-" + i + "-" + UUID.randomUUID() + "@example.com";
            recipients.add(recipient);
            persistThrottledDelivery(notification.getId(), recipient);
        }
        ExecutorService executor = Executors.newFixedThreadPool(SWEEPERS);
        try {
            CyclicBarrier start = new CyclicBarrier(SWEEPERS);
            List<Future<?>> sweeps = new ArrayList<>();
            for (int i = 0; i < SWEEPERS; i++) {
                DeliveryRetryScheduler sweeper = scheduler(router, CONTENDED_ROWS);
                sweeps.add(executor.submit(() -> {
                    start.await();
                    sweeper.sweep();
                    return null;
                }));
            }
            for (Future<?> sweep : sweeps) {
                sweep.get(30, TimeUnit.SECONDS);
            }

            assertThat(recipients)
                    .allSatisfy(recipient -> assertThat(counting.deliveriesTo(recipient))
                            .as("deliveries to %s", recipient)
                            .isOne());
            assertThat(meterRegistry
                            .get("notifications.retried")
                            .tag("channel", "EMAIL")
                            .counter()
                            .count())
                    .isEqualTo(CONTENDED_ROWS);
            assertThat(deliveryRepository.findAll().stream()
                            .filter(d -> d.getNotificationId().equals(notification.getId()))
                            .map(NotificationDelivery::getStatus))
                    .hasSize(CONTENDED_ROWS)
                    .containsOnly(DeliveryStatus.SENT);
        } finally {
            executor.shutdownNow();
            deliveryRepository.deleteAll(deliveryRepository.findAll().stream()
                    .filter(d -> d.getNotificationId().equals(notification.getId()))
                    .toList());
            notificationRepository.deleteById(notification.getId());
        }
    }

    private static final class CountingChannel implements NotificationChannel {

        private final Map<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();

        @Override
        public Channel type() {
            return Channel.EMAIL;
        }

        @Override
        public void deliver(RenderedNotification notification, String recipient) {
            deliveries.computeIfAbsent(recipient, key -> new AtomicInteger()).incrementAndGet();
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        int deliveriesTo(String recipient) {
            AtomicInteger count = deliveries.get(recipient);
            return count == null ? 0 : count.get();
        }
    }
}
