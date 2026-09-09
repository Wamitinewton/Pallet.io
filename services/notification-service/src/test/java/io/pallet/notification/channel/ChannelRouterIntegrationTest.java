package io.pallet.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.pallet.common.test.annotations.RepositoryTest;
import io.pallet.notification.audience.Recipient;
import io.pallet.notification.domain.Audience;
import io.pallet.notification.domain.Channel;
import io.pallet.notification.domain.DeliveryStatus;
import io.pallet.notification.domain.Notification;
import io.pallet.notification.domain.NotificationDelivery;
import io.pallet.notification.repository.NotificationDeliveryRepository;
import io.pallet.notification.repository.NotificationRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@RepositoryTest
class ChannelRouterIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    private NotificationChannel emailChannel;
    private ChannelRouter router;

    @BeforeEach
    void setUp() {
        emailChannel = mock(NotificationChannel.class);
        when(emailChannel.type()).thenReturn(Channel.EMAIL);
        router = new ChannelRouter(List.of(emailChannel, new InAppChannel()), deliveryRepository);
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

    @Test
    void singleRecipientBothChannelsWritesExactlyTwoRows() throws Exception {
        Notification notification = persistNotification();
        Recipient recipient = new Recipient("user-1", "user1@example.com");

        router.fanOut(notification, Set.of(Channel.EMAIL, Channel.IN_APP), List.of(recipient));

        assertThat(deliveryRepository.findAll())
                .extracting(NotificationDelivery::getChannel, NotificationDelivery::getRecipient)
                .containsExactlyInAnyOrder(tuple(Channel.EMAIL, "user1@example.com"), tuple(Channel.IN_APP, "user-1"));
        verify(emailChannel, times(1)).deliver(any(), eq("user1@example.com"));
    }

    @Test
    void broadcastWithThreeRecipientsAndTwoChannelsWritesSixRows() {
        Notification notification = persistNotification();
        List<Recipient> recipients = List.of(
                new Recipient("user-1", "user1@example.com"),
                new Recipient("user-2", "user2@example.com"),
                new Recipient("user-3", "user3@example.com"));

        router.fanOut(notification, Set.of(Channel.EMAIL, Channel.IN_APP), recipients);

        assertThat(deliveryRepository.findAll()).hasSize(6);
    }

    @Test
    void repeatedFanOutIsANoOpForAlreadyHandledRows() throws Exception {
        Notification notification = persistNotification();
        Recipient recipient = new Recipient("user-1", "user1@example.com");

        router.fanOut(notification, Set.of(Channel.EMAIL, Channel.IN_APP), List.of(recipient));
        long countAfterFirst = deliveryRepository.count();

        router.fanOut(notification, Set.of(Channel.EMAIL, Channel.IN_APP), List.of(recipient));

        assertThat(deliveryRepository.count()).isEqualTo(countAfterFirst);
        verify(emailChannel, times(1)).deliver(any(), eq("user1@example.com"));
        NotificationDelivery emailDelivery = deliveryRepository
                .findByNotificationIdAndChannelAndRecipient(notification.getId(), Channel.EMAIL, "user1@example.com")
                .orElseThrow();
        assertThat(emailDelivery.getStatus()).isEqualTo(DeliveryStatus.SENT);
    }

    @Test
    void oneRecipientFailingLeavesOthersIndependentlySent() throws Exception {
        Notification notification = persistNotification();
        doThrow(new ChannelDeliveryException("smtp down", new RuntimeException()))
                .when(emailChannel)
                .deliver(any(), eq("bad@example.com"));
        List<Recipient> recipients =
                List.of(new Recipient("user-1", "good@example.com"), new Recipient("user-2", "bad@example.com"));

        router.fanOut(notification, Set.of(Channel.EMAIL), recipients);

        NotificationDelivery good = deliveryRepository
                .findByNotificationIdAndChannelAndRecipient(notification.getId(), Channel.EMAIL, "good@example.com")
                .orElseThrow();
        NotificationDelivery bad = deliveryRepository
                .findByNotificationIdAndChannelAndRecipient(notification.getId(), Channel.EMAIL, "bad@example.com")
                .orElseThrow();
        assertThat(good.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(bad.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(bad.getLastError()).isEqualTo("smtp down");
    }

    @Test
    void resumingAMidBroadcastCrashOnlyDeliversTheMissingRecipient() throws Exception {
        Notification notification = persistNotification();
        List<Recipient> recipients = List.of(
                new Recipient("user-1", "user1@example.com"),
                new Recipient("user-2", "user2@example.com"),
                new Recipient("user-3", "user3@example.com"));
        NotificationDelivery already1 =
                new NotificationDelivery(notification.getId(), Channel.EMAIL, "user1@example.com");
        already1.markSent();
        NotificationDelivery already2 =
                new NotificationDelivery(notification.getId(), Channel.EMAIL, "user2@example.com");
        already2.markSent();
        deliveryRepository.saveAllAndFlush(List.of(already1, already2));

        router.fanOut(notification, Set.of(Channel.EMAIL), recipients);

        verify(emailChannel, never()).deliver(any(), eq("user1@example.com"));
        verify(emailChannel, never()).deliver(any(), eq("user2@example.com"));
        verify(emailChannel, times(1)).deliver(any(), eq("user3@example.com"));
        assertThat(deliveryRepository.findAll()).hasSize(3);
    }
}
