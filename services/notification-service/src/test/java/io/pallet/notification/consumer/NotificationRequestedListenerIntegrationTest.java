package io.pallet.notification.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.events.NotificationRequested;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.notification.audience.OrgMember;
import io.pallet.notification.audience.OrgMemberStatus;
import io.pallet.notification.audience.OrgMembershipRepository;
import io.pallet.notification.domain.Audience;
import io.pallet.notification.domain.Channel;
import io.pallet.notification.domain.DeliveryStatus;
import io.pallet.notification.domain.Notification;
import io.pallet.notification.domain.NotificationDelivery;
import io.pallet.notification.repository.NotificationDeliveryRepository;
import io.pallet.notification.repository.NotificationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * {@code GREEN_MAIL.stop()} in {@link #anEmailFailureIsRecordedButStillAcksAndNeverReachesTheDeadLetterTopic()}
 * makes SMTP fail for the rest of the class (the static {@link GreenMailExtension} only starts
 * and stops once per class), so that test is ordered to run last.
 */
@IntegrationTest
@Import(NotificationRequestedListenerIntegrationTest.DeadLetterCapture.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationRequestedListenerIntegrationTest {

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication());

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", ServerSetupTest.SMTP::getPort);
    }

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    @Autowired
    private OrgMembershipRepository orgMembershipRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private DeadLetterCapture deadLetterCapture;

    @Test
    @Order(1)
    void singleRecipientBothDefaultChannelsAreDeliveredAndPersisted() throws Exception {
        String orgId = "org-" + UUID.randomUUID();
        NotificationRequested event = NotificationRequested.of(
                orgId, "WELCOME", "ada@example.com", null, null, Map.of("name", "Ada", "orgName", "Acme"));

        publisher.publish(event);

        Notification notification = awaitNotification(event.eventId());
        assertThat(notification.getAudience()).isEqualTo(Audience.SINGLE);
        List<NotificationDelivery> deliveries = awaitDeliveries(notification.getId(), 2);
        assertThat(deliveries)
                .extracting(NotificationDelivery::getChannel, NotificationDelivery::getStatus)
                .containsExactlyInAnyOrder(
                        tuple(Channel.EMAIL, DeliveryStatus.SENT), tuple(Channel.IN_APP, DeliveryStatus.SENT));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> assertThat(GREEN_MAIL.getReceivedMessages()).hasSize(1));
        assertThat(GREEN_MAIL.getReceivedMessages()[0].getSubject()).isEqualTo("Welcome to Pallet, Ada");
    }

    @Test
    @Order(2)
    void redeliveryOfTheSameEventIdIsANoOp() throws Exception {
        String orgId = "org-" + UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> variables = Map.of("name", "Bo", "orgName", "Acme");
        for (int i = 0; i < 2; i++) {
            publisher.publish(new NotificationRequested(
                    eventId,
                    NotificationRequested.TYPE,
                    orgId,
                    Instant.now(),
                    "WELCOME",
                    "bo@example.com",
                    null,
                    null,
                    variables,
                    null));
        }

        Notification notification = awaitNotification(eventId);
        List<NotificationDelivery> deliveries = awaitDeliveries(notification.getId(), 2);

        assertThat(deliveries).hasSize(2);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> assertThat(GREEN_MAIL.getReceivedMessages()).hasSize(1));
    }

    @Test
    @Order(3)
    void unknownNotificationTypeSkipsStraightToTheDeadLetterTopic() {
        String orgId = "org-" + UUID.randomUUID();
        NotificationRequested event =
                NotificationRequested.of(orgId, "DOES_NOT_EXIST", "z@example.com", null, null, Map.of());
        Instant start = Instant.now();

        publisher.publish(event);

        ConsumerRecord<String, JsonNode> deadLetter =
                deadLetterCapture.await(event.eventId().toString(), Duration.ofSeconds(5));
        assertThat(deadLetter.value().get("eventId").asString())
                .isEqualTo(event.eventId().toString());
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(5));
        assertThat(notificationRepository.findBySourceEventId(event.eventId())).isEmpty();
    }

    @Test
    @Order(4)
    void orgBroadcastWithNoMembersCreatesNotificationButNoDeliveries() {
        String orgId = "org-" + UUID.randomUUID();
        double before = broadcastEmptyCount();
        NotificationRequested event =
                NotificationRequested.broadcast(orgId, "WELCOME", null, Map.of("name", "Org", "orgName", "Acme"));

        publisher.publish(event);

        Notification notification = awaitNotification(event.eventId());
        assertThat(notification.getAudience()).isEqualTo(Audience.ORG);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(broadcastEmptyCount()).isEqualTo(before + 1));
        assertThat(deliveriesFor(notification.getId())).isEmpty();
    }

    @Test
    @Order(5)
    void orgBroadcastWithThreeActiveMembersFansOutToAllResolvedChannels() {
        String orgId = "org-" + UUID.randomUUID();
        orgMembershipRepository.saveAll(List.of(
                new OrgMember(orgId, "user-1", "user1@example.com", OrgMemberStatus.ACTIVE),
                new OrgMember(orgId, "user-2", "user2@example.com", OrgMemberStatus.ACTIVE),
                new OrgMember(orgId, "user-3", "user3@example.com", OrgMemberStatus.ACTIVE)));
        NotificationRequested event =
                NotificationRequested.broadcast(orgId, "WELCOME", null, Map.of("name", "Team", "orgName", "Acme"));

        publisher.publish(event);

        Notification notification = awaitNotification(event.eventId());
        List<NotificationDelivery> deliveries = awaitDeliveries(notification.getId(), 6);
        assertThat(deliveries).hasSize(6);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> assertThat(GREEN_MAIL.getReceivedMessages()).hasSize(3));
    }

    @Test
    @Order(6)
    void anEmailFailureIsRecordedButStillAcksAndNeverReachesTheDeadLetterTopic() {
        GREEN_MAIL.stop();
        String orgId = "org-" + UUID.randomUUID();
        NotificationRequested event = NotificationRequested.of(
                orgId, "WELCOME", "down@example.com", null, null, Map.of("name", "Cam", "orgName", "Acme"));

        publisher.publish(event);

        Notification notification = awaitNotification(event.eventId());
        awaitDeliveries(notification.getId(), 2);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(deliveriesFor(notification.getId()))
                        .extracting(NotificationDelivery::getChannel, NotificationDelivery::getStatus)
                        .containsExactlyInAnyOrder(
                                tuple(Channel.EMAIL, DeliveryStatus.FAILED),
                                tuple(Channel.IN_APP, DeliveryStatus.SENT)));
        NotificationDelivery email = deliveriesFor(notification.getId()).stream()
                .filter(delivery -> delivery.getChannel() == Channel.EMAIL)
                .findFirst()
                .orElseThrow();
        assertThat(email.getLastError()).isNotBlank();
        assertThat(deadLetterCapture.contains(event.eventId().toString())).isFalse();
    }

    private double broadcastEmptyCount() {
        Counter counter = meterRegistry.find("notifications.broadcast_empty").counter();
        return counter == null ? 0.0 : counter.count();
    }

    private List<NotificationDelivery> deliveriesFor(UUID notificationId) {
        return deliveryRepository.findAll().stream()
                .filter(delivery -> delivery.getNotificationId().equals(notificationId))
                .toList();
    }

    private Notification awaitNotification(UUID eventId) {
        AtomicReference<Notification> found = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            var notification = notificationRepository.findBySourceEventId(eventId);
            notification.ifPresent(found::set);
            return notification.isPresent();
        });
        return found.get();
    }

    /**
     * {@code insertPendingIfAbsent} commits a row as {@code PENDING} in its own transaction before
     * the (possibly slow) channel send runs and a second transaction records the terminal status,
     * so a row can be visible here before its delivery has actually finished. Waiting only for
     * {@code expectedCount} rows to exist — without also waiting past {@code PENDING} — lets the
     * caller observe a delivery mid-flight and race the assertion that follows.
     */
    private List<NotificationDelivery> awaitDeliveries(UUID notificationId, int expectedCount) {
        AtomicReference<List<NotificationDelivery>> found = new AtomicReference<>(List.of());
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            List<NotificationDelivery> deliveries = deliveriesFor(notificationId);
            found.set(deliveries);
            return deliveries.size() == expectedCount
                    && deliveries.stream().noneMatch(delivery -> delivery.getStatus() == DeliveryStatus.PENDING);
        });
        return found.get();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DeadLetterCapture {

        private final Map<String, ConsumerRecord<String, JsonNode>> byEventId = new ConcurrentHashMap<>();

        @KafkaListener(
                topics = Topics.NOTIFICATION_REQUESTED + Topics.DLT_SUFFIX,
                groupId = "notification-requested-listener-test-dlt")
        void onDeadLetter(ConsumerRecord<String, JsonNode> record) {
            JsonNode eventId = record.value().get("eventId");
            if (eventId != null) {
                byEventId.put(eventId.asString(), record);
            }
        }

        boolean contains(String eventId) {
            return byEventId.containsKey(eventId);
        }

        ConsumerRecord<String, JsonNode> await(String eventId, Duration timeout) {
            org.awaitility.Awaitility.await().atMost(timeout).until(() -> byEventId.containsKey(eventId));
            return byEventId.get(eventId);
        }
    }
}
