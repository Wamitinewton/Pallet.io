package io.pallet.notification.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.common.api.PageQuery;
import io.pallet.common.test.annotations.RepositoryTest;
import io.pallet.notification.domain.Audience;
import io.pallet.notification.domain.Channel;
import io.pallet.notification.domain.Notification;
import io.pallet.notification.domain.NotificationDelivery;
import io.pallet.notification.repository.NotificationDeliveryRepository;
import io.pallet.notification.repository.NotificationRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@RepositoryTest
class NotificationQueryServiceIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    private NotificationQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService =
                new NotificationQueryService(notificationRepository, deliveryRepository, new SimpleMeterRegistry());
    }

    private Notification persistNotification(String title) {
        Notification notification = new Notification(
                "org-1", "welcome-email", UUID.randomUUID(), null, Audience.SINGLE, title, "<p>Body</p>", Map.of());
        return notificationRepository.saveAndFlush(notification);
    }

    /**
     * Every delivery gets its own {@link Notification}: {@code (notification_id, channel,
     * recipient)} is unique, so two deliveries on the same channel to the same recipient can't
     * share one notification row.
     */
    private NotificationDelivery persistDelivery(Channel channel, String recipient) throws InterruptedException {
        Thread.sleep(2);
        UUID notificationId = persistNotification("Welcome").getId();
        return deliveryRepository.saveAndFlush(new NotificationDelivery(notificationId, channel, recipient));
    }

    @Test
    void listReturnsOnlyTheRecipientsInAppDeliveriesNewestFirst() throws InterruptedException {
        String userA = "user-a-" + UUID.randomUUID();
        String userB = "user-b-" + UUID.randomUUID();

        NotificationDelivery older = persistDelivery(Channel.IN_APP, userA);
        NotificationDelivery newer = persistDelivery(Channel.IN_APP, userA);
        persistDelivery(Channel.EMAIL, userA);
        persistDelivery(Channel.IN_APP, userB);

        var page = queryService.list(userA, new PageQuery(0, 10, null), false);

        assertThat(page.content()).hasSize(2);
        assertThat(page.content().get(0).deliveryId()).isEqualTo(newer.getId());
        assertThat(page.content().get(1).deliveryId()).isEqualTo(older.getId());
        assertThat(page.content()).allSatisfy(dto -> assertThat(dto.title()).isEqualTo("Welcome"));
    }

    @Test
    void listWithUnreadOnlyExcludesAlreadyReadDeliveries() throws InterruptedException {
        String userA = "user-a-" + UUID.randomUUID();
        NotificationDelivery unread = persistDelivery(Channel.IN_APP, userA);
        NotificationDelivery read = persistDelivery(Channel.IN_APP, userA);
        read.markRead();
        deliveryRepository.saveAndFlush(read);

        var page = queryService.list(userA, new PageQuery(0, 10, null), true);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().getFirst().deliveryId()).isEqualTo(unread.getId());
    }

    @Test
    void getForSomeoneElsesDeliveryReturnsEmpty() throws InterruptedException {
        String owner = "user-owner-" + UUID.randomUUID();
        String stranger = "user-stranger-" + UUID.randomUUID();
        NotificationDelivery delivery = persistDelivery(Channel.IN_APP, owner);

        assertThat(queryService.get(owner, delivery.getId())).isPresent();
        assertThat(queryService.get(stranger, delivery.getId())).isEmpty();
    }

    @Test
    void markReadIsIdempotentAndMonotonic() throws InterruptedException {
        String userA = "user-a-" + UUID.randomUUID();
        NotificationDelivery delivery = persistDelivery(Channel.IN_APP, userA);

        queryService.markRead(userA, delivery.getId());
        Instant firstReadAt =
                deliveryRepository.findById(delivery.getId()).orElseThrow().getReadAt();
        assertThat(firstReadAt).isNotNull();

        queryService.markRead(userA, delivery.getId());
        Instant secondReadAt =
                deliveryRepository.findById(delivery.getId()).orElseThrow().getReadAt();
        assertThat(secondReadAt).isEqualTo(firstReadAt);
    }

    @Test
    void markAllReadClearsOnlyTheCallingRecipientsUnreadRows() throws InterruptedException {
        String userA = "user-a-" + UUID.randomUUID();
        String userB = "user-b-" + UUID.randomUUID();
        NotificationDelivery userADelivery1 = persistDelivery(Channel.IN_APP, userA);
        NotificationDelivery userADelivery2 = persistDelivery(Channel.IN_APP, userA);
        NotificationDelivery userBDelivery = persistDelivery(Channel.IN_APP, userB);

        queryService.markAllRead(userA);

        assertThat(deliveryRepository
                        .findById(userADelivery1.getId())
                        .orElseThrow()
                        .getReadAt())
                .isNotNull();
        assertThat(deliveryRepository
                        .findById(userADelivery2.getId())
                        .orElseThrow()
                        .getReadAt())
                .isNotNull();
        assertThat(deliveryRepository
                        .findById(userBDelivery.getId())
                        .orElseThrow()
                        .getReadAt())
                .isNull();
    }

    @Test
    void unreadCountMatchesAHandCountedFixture() throws InterruptedException {
        String userA = "user-a-" + UUID.randomUUID();
        persistDelivery(Channel.IN_APP, userA);
        persistDelivery(Channel.IN_APP, userA);
        NotificationDelivery read = persistDelivery(Channel.IN_APP, userA);
        read.markRead();
        deliveryRepository.saveAndFlush(read);
        persistDelivery(Channel.EMAIL, userA);

        assertThat(queryService.unreadCount(userA)).isEqualTo(2);
    }
}
