package io.pallet.notification.audience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.pallet.common.events.NotificationRequested;
import io.pallet.common.events.OrgDeleted;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.events.OrgMemberRemoved;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.MessagingProperties;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.notification.domain.Notification;
import io.pallet.notification.domain.NotificationDelivery;
import io.pallet.notification.repository.NotificationDeliveryRepository;
import io.pallet.notification.repository.NotificationRepository;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

@IntegrationTest
@Import(OrgMembershipEventListenerIntegrationTest.DeadLetterCapture.class)
@TestPropertySource(properties = "pallet.notification.email.from=no-reply@pallet.local")
class OrgMembershipEventListenerIntegrationTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(15);

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
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private OrgMembershipRepository membershipRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    @Autowired
    private DeadLetterCapture deadLetterCapture;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private MessagingProperties messagingProperties;

    @BeforeEach
    void ensureListenersHaveSettled() {
        for (String id : List.of(
                "org-member-added-listener",
                "org-member-removed-listener",
                "org-deleted-listener",
                "notification-requested-listener")) {
            ContainerTestUtils.waitForAssignment(
                    listenerRegistry.getListenerContainer(id), messagingProperties.topicPartitions());
        }
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private OrgMemberStatus statusOf(String orgId, String userId) {
        return membershipRepository
                .findById(new OrgMemberId(orgId, userId))
                .map(OrgMember::getStatus)
                .orElse(null);
    }

    private void awaitStatus(String orgId, String userId, OrgMemberStatus expected) {
        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(statusOf(orgId, userId)).isEqualTo(expected));
    }

    private OrgMemberAdded added(String orgId, String userId, Instant at) {
        return new OrgMemberAdded(UUID.randomUUID(), OrgMemberAdded.TYPE, orgId, at, userId, userId + "@example.com");
    }

    private OrgMemberRemoved removed(String orgId, String userId, Instant at) {
        return new OrgMemberRemoved(
                UUID.randomUUID(), OrgMemberRemoved.TYPE, orgId, at, userId, userId + "@example.com");
    }

    private List<NotificationDelivery> awaitBroadcastDeliveries(String orgId, int expected) {
        NotificationRequested broadcast =
                NotificationRequested.broadcast(orgId, "WELCOME", null, Map.of("name", "Team", "orgName", "Acme"));
        publisher.publish(broadcast);

        AtomicReference<Notification> notification = new AtomicReference<>();
        await().atMost(AWAIT_TIMEOUT).until(() -> {
            var found = notificationRepository.findBySourceEventId(broadcast.eventId());
            found.ifPresent(notification::set);
            return found.isPresent();
        });
        AtomicReference<List<NotificationDelivery>> deliveries = new AtomicReference<>(List.of());
        await().atMost(AWAIT_TIMEOUT).until(() -> {
            List<NotificationDelivery> current = deliveryRepository.findAll().stream()
                    .filter(delivery -> delivery.getNotificationId()
                            .equals(notification.get().getId()))
                    .toList();
            deliveries.set(current);
            return current.size() == expected;
        });
        return deliveries.get();
    }

    @Test
    void memberAddedCreatesAnActiveRowThatAnOrgBroadcastReaches() {
        String orgId = "org-" + unique();
        String userId = "user-" + unique();

        publisher.publish(added(orgId, userId, Instant.now()));
        awaitStatus(orgId, userId, OrgMemberStatus.ACTIVE);

        List<NotificationDelivery> deliveries = awaitBroadcastDeliveries(orgId, 2);
        assertThat(deliveries)
                .extracting(NotificationDelivery::getRecipient)
                .containsOnly(userId + "@example.com", userId);
    }

    @Test
    void memberRemovedIsExcludedFromTheNextBroadcast() {
        String orgId = "org-" + unique();
        String staying = "staying-" + unique();
        String leaving = "leaving-" + unique();
        Instant base = Instant.now();
        publisher.publish(added(orgId, staying, base));
        publisher.publish(added(orgId, leaving, base));
        awaitStatus(orgId, staying, OrgMemberStatus.ACTIVE);
        awaitStatus(orgId, leaving, OrgMemberStatus.ACTIVE);

        publisher.publish(removed(orgId, leaving, base.plusSeconds(1)));
        awaitStatus(orgId, leaving, OrgMemberStatus.REMOVED);

        List<NotificationDelivery> deliveries = awaitBroadcastDeliveries(orgId, 2);
        assertThat(deliveries)
                .extracting(NotificationDelivery::getRecipient)
                .allMatch(recipient -> recipient.contains(staying) || recipient.equals(staying));
    }

    @Test
    void removedFollowedByAnOlderAddedStaysRemoved() {
        String orgId = "org-" + unique();
        String userId = "user-" + unique();
        Instant now = Instant.now();

        publisher.publish(removed(orgId, userId, now));
        awaitStatus(orgId, userId, OrgMemberStatus.REMOVED);
        publisher.publish(added(orgId, userId, now.minusSeconds(30)));
        publisher.publish(added(orgId, "sentinel", now));
        awaitStatus(orgId, "sentinel", OrgMemberStatus.ACTIVE);

        assertThat(statusOf(orgId, userId)).isEqualTo(OrgMemberStatus.REMOVED);
    }

    @Test
    void redeliveryOfEachEventLeavesTheProjectionUnchanged() {
        String orgId = "org-" + unique();
        String userId = "user-" + unique();
        Instant now = Instant.now();
        OrgMemberAdded add = added(orgId, userId, now);
        OrgMemberRemoved remove = removed(orgId, userId, now.plusSeconds(1));

        publisher.publish(add);
        awaitStatus(orgId, userId, OrgMemberStatus.ACTIVE);
        publisher.publish(add);
        publisher.publish(remove);
        awaitStatus(orgId, userId, OrgMemberStatus.REMOVED);
        publisher.publish(remove);
        publisher.publish(add);
        publisher.publish(added(orgId, "sentinel", now));
        awaitStatus(orgId, "sentinel", OrgMemberStatus.ACTIVE);

        assertThat(statusOf(orgId, userId)).isEqualTo(OrgMemberStatus.REMOVED);
    }

    @Test
    void orgDeletedRemovesEveryMemberOfThatOrgAndLeavesOthersUntouched() {
        String orgId = "org-" + unique();
        String otherOrgId = "org-" + unique();
        Instant now = Instant.now();
        publisher.publish(added(orgId, "u1", now));
        publisher.publish(added(orgId, "u2", now));
        publisher.publish(added(otherOrgId, "u3", now));
        awaitStatus(orgId, "u1", OrgMemberStatus.ACTIVE);
        awaitStatus(orgId, "u2", OrgMemberStatus.ACTIVE);
        awaitStatus(otherOrgId, "u3", OrgMemberStatus.ACTIVE);

        publisher.publish(new OrgDeleted(UUID.randomUUID(), OrgDeleted.TYPE, orgId, now.plusSeconds(1), "owner"));

        awaitStatus(orgId, "u1", OrgMemberStatus.REMOVED);
        awaitStatus(orgId, "u2", OrgMemberStatus.REMOVED);
        assertThat(statusOf(otherOrgId, "u3")).isEqualTo(OrgMemberStatus.ACTIVE);
    }

    @Test
    void aMalformedEventLandsOnTheDeadLetterTopic() {
        String orgId = "org-" + unique();
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> malformed = Map.of(
                "eventId",
                eventId,
                "eventType",
                OrgMemberAdded.TYPE,
                "orgId",
                orgId,
                "occurredAt",
                Instant.now().toString(),
                "email",
                "nobody@example.com");

        kafkaTemplate.send(Topics.ORG_MEMBER_ADDED, orgId, malformed);

        assertThat(deadLetterCapture.await(eventId, Duration.ofSeconds(30))).isNotNull();
        assertThat(membershipRepository.findByOrgIdAndStatus(orgId, OrgMemberStatus.ACTIVE))
                .isEmpty();
    }

    @Test
    void orgInviteRendersThroughTheRealTemplateAndNeverLogsTheAcceptUrl() throws Exception {
        String acceptUrl = "https://app.pallet.test/invites/" + unique() + "." + unique();
        String recipient = "invitee-" + unique() + "@example.com";
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try {
            publisher.publish(NotificationRequested.of(
                    "org-" + unique(),
                    "ORG_INVITE",
                    recipient,
                    null,
                    null,
                    Map.of("orgName", "Acme", "inviterName", "Ada", "role", "developer", "acceptUrl", acceptUrl)));

            AtomicReference<MimeMessage> received = new AtomicReference<>();
            await().atMost(AWAIT_TIMEOUT).until(() -> {
                for (MimeMessage message : GREEN_MAIL.getReceivedMessages()) {
                    if (message.getAllRecipients()[0].toString().equals(recipient)) {
                        received.set(message);
                        return true;
                    }
                }
                return false;
            });

            assertThat(received.get().getSubject()).isEqualTo("Ada invited you to join Acme on Pallet");
            assertThat(com.icegreen.greenmail.util.GreenMailUtil.getBody(received.get()))
                    .contains(acceptUrl);
            assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).noneMatch(m -> m.contains(acceptUrl));
        } finally {
            root.detachAppender(logs);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DeadLetterCapture {

        private final Map<String, ConsumerRecord<String, JsonNode>> byEventId = new ConcurrentHashMap<>();

        @KafkaListener(
                topics = Topics.ORG_MEMBER_ADDED + Topics.DLT_SUFFIX,
                groupId = "org-membership-listener-test-dlt")
        void onDeadLetter(ConsumerRecord<String, JsonNode> record) {
            JsonNode eventId = record.value().get("eventId");
            if (eventId != null) {
                byEventId.put(eventId.asString(), record);
            }
        }

        ConsumerRecord<String, JsonNode> await(String eventId, Duration timeout) {
            org.awaitility.Awaitility.await().atMost(timeout).until(() -> byEventId.containsKey(eventId));
            return byEventId.get(eventId);
        }
    }
}
