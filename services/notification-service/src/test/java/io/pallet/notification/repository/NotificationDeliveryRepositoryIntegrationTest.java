package io.pallet.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.annotations.RepositoryTest;
import io.pallet.notification.domain.Audience;
import io.pallet.notification.domain.Channel;
import io.pallet.notification.domain.DeliveryStatus;
import io.pallet.notification.domain.Notification;
import io.pallet.notification.domain.NotificationDelivery;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

@RepositoryTest
class NotificationDeliveryRepositoryIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

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

    private static String uniqueRecipient() {
        return "recipient-" + UUID.randomUUID();
    }

    @Test
    void savesAndFetchesByNotificationChannelAndRecipient() {
        Notification notification = persistNotification();
        String recipient = uniqueRecipient();
        NotificationDelivery delivery = new NotificationDelivery(notification.getId(), Channel.EMAIL, recipient);

        deliveryRepository.saveAndFlush(delivery);

        Optional<NotificationDelivery> found = deliveryRepository.findByNotificationIdAndChannelAndRecipient(
                notification.getId(), Channel.EMAIL, recipient);
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(delivery.getId());
    }

    @Test
    void aSecondInsertWithTheSameTripleIsRejected() {
        Notification notification = persistNotification();
        String recipient = uniqueRecipient();
        deliveryRepository.saveAndFlush(new NotificationDelivery(notification.getId(), Channel.EMAIL, recipient));

        assertThatThrownBy(() -> deliveryRepository.saveAndFlush(
                        new NotificationDelivery(notification.getId(), Channel.EMAIL, recipient)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deliveriesForTheSameNotificationOnDifferentChannelsDoNotCollide() {
        Notification notification = persistNotification();
        String recipient = uniqueRecipient();

        NotificationDelivery email = deliveryRepository.saveAndFlush(
                new NotificationDelivery(notification.getId(), Channel.EMAIL, recipient));
        NotificationDelivery inApp = deliveryRepository.saveAndFlush(
                new NotificationDelivery(notification.getId(), Channel.IN_APP, recipient));

        assertThat(email.getId()).isNotEqualTo(inApp.getId());
    }

    @Test
    void markSentMarkFailedAndMarkReadProduceExpectedColumnState() {
        Notification notification = persistNotification();
        NotificationDelivery delivery = deliveryRepository.saveAndFlush(
                new NotificationDelivery(notification.getId(), Channel.EMAIL, uniqueRecipient()));

        delivery.markSent();
        deliveryRepository.saveAndFlush(delivery);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(delivery.getSentAt()).isNotNull();
        assertThat(delivery.getAttemptCount()).isEqualTo(1);

        delivery.markFailed("smtp timeout");
        deliveryRepository.saveAndFlush(delivery);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getLastError()).isEqualTo("smtp timeout");
        assertThat(delivery.getAttemptCount()).isEqualTo(2);

        delivery.markRead();
        deliveryRepository.saveAndFlush(delivery);
        Instant firstReadAt = delivery.getReadAt();
        assertThat(firstReadAt).isNotNull();

        delivery.markRead();
        deliveryRepository.saveAndFlush(delivery);
        assertThat(delivery.getReadAt()).isEqualTo(firstReadAt);
    }

    @Test
    void findByRecipientAndChannelReturnsOnlyInAppRowsForAMixedFixture() {
        Notification notification = persistNotification();
        String recipient = uniqueRecipient();
        deliveryRepository.saveAndFlush(new NotificationDelivery(notification.getId(), Channel.EMAIL, recipient));
        deliveryRepository.saveAndFlush(new NotificationDelivery(notification.getId(), Channel.IN_APP, recipient));

        var page = deliveryRepository.findByRecipientAndChannelOrderByCreatedAtDesc(
                recipient, Channel.IN_APP, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().getChannel()).isEqualTo(Channel.IN_APP);
    }

    @Test
    void countByRecipientAndChannelAndReadAtIsNullMatchesAMixedFixture() {
        String recipient = uniqueRecipient();
        NotificationDelivery unread = deliveryRepository.saveAndFlush(
                new NotificationDelivery(persistNotification().getId(), Channel.IN_APP, recipient));
        NotificationDelivery read = deliveryRepository.saveAndFlush(
                new NotificationDelivery(persistNotification().getId(), Channel.IN_APP, recipient));
        read.markRead();
        deliveryRepository.saveAndFlush(read);
        deliveryRepository.saveAndFlush(
                new NotificationDelivery(persistNotification().getId(), Channel.EMAIL, recipient));

        assertThat(unread.getReadAt()).isNull();
        long unreadCount = deliveryRepository.countByRecipientAndChannelAndReadAtIsNull(recipient, Channel.IN_APP);
        assertThat(unreadCount).isEqualTo(1);
    }
}
