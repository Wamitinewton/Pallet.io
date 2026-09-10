package io.pallet.notification.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.events.NotificationRequested;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import io.pallet.notification.domain.Audience;
import io.pallet.notification.domain.Channel;
import io.pallet.notification.domain.DeliveryStatus;
import io.pallet.notification.domain.Notification;
import io.pallet.notification.domain.NotificationDelivery;
import io.pallet.notification.repository.NotificationDeliveryRepository;
import io.pallet.notification.repository.NotificationRepository;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Confirms checkpoint 11's counters and histogram are wired at the points the pipeline already
 * established, not that delivery/read behavior changed. {@code GREEN_MAIL.stop()} in
 * {@link #emailFailureIncrementsFailedButNotSentForEmail()} makes SMTP fail for the rest of the
 * class, so that test is ordered to run last.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(KeycloakTestContainerConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationMetricsIntegrationTest {

    private static final String SENT_METRIC = "notifications.sent";
    private static final String FAILED_METRIC = "notifications.failed";
    private static final String RETRIED_METRIC = "notifications.retried";
    private static final String READ_METRIC = "notifications.read";

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", ServerSetupTest.SMTP::getPort);
        registry.add("pallet.notification.rate-limit.sweep-interval", () -> "1s");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    private static RequestPostProcessor userJwt(String userId) {
        return jwt().jwt(builder -> builder.subject(userId));
    }

    private Notification persistNotification() {
        Notification notification = new Notification(
                "org-" + UUID.randomUUID(),
                "welcome-email",
                UUID.randomUUID(),
                null,
                Audience.SINGLE,
                "Welcome!",
                "<p>Welcome aboard.</p>",
                Map.of());
        return notificationRepository.saveAndFlush(notification);
    }

    private double counter(String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @Order(1)
    void markReadTwiceIncrementsReadCounterExactlyOnce() throws Exception {
        String userId = "user-" + UUID.randomUUID();
        NotificationDelivery delivery = deliveryRepository.saveAndFlush(
                new NotificationDelivery(persistNotification().getId(), Channel.IN_APP, userId));
        double before = counter(READ_METRIC);

        mvc.perform(patch("/api/v1/notifications/" + delivery.getId() + "/read").with(userJwt(userId)))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/notifications/" + delivery.getId() + "/read").with(userJwt(userId)))
                .andExpect(status().isOk());

        assertThat(counter(READ_METRIC)).isEqualTo(before + 1);
    }

    @Test
    @Order(2)
    void sweepIncrementsRetriedCounterForEachThrottledRowItProcesses() {
        NotificationDelivery delivery =
                new NotificationDelivery(persistNotification().getId(), Channel.IN_APP, "user-" + UUID.randomUUID());
        delivery.markThrottled();
        deliveryRepository.saveAndFlush(delivery);
        double before = counter(RETRIED_METRIC, "channel", "IN_APP");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(counter(RETRIED_METRIC, "channel", "IN_APP")).isEqualTo(before + 1);
            assertThat(deliveryRepository
                            .findById(delivery.getId())
                            .orElseThrow()
                            .getStatus())
                    .isEqualTo(DeliveryStatus.SENT);
        });
    }

    @Test
    @Order(3)
    void singleEventWithBothChannelsIncrementsSentForEachChannel() {
        double beforeEmail = counter(SENT_METRIC, "channel", "EMAIL");
        double beforeInApp = counter(SENT_METRIC, "channel", "IN_APP");
        NotificationRequested event = NotificationRequested.of(
                "org-" + UUID.randomUUID(),
                "WELCOME",
                "metrics-ok@example.com",
                null,
                null,
                Map.of("name", "Ada", "orgName", "Acme"));

        publisher.publish(event);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(counter(SENT_METRIC, "channel", "EMAIL")).isEqualTo(beforeEmail + 1);
            assertThat(counter(SENT_METRIC, "channel", "IN_APP")).isEqualTo(beforeInApp + 1);
        });
    }

    @Test
    @Order(4)
    void emailFailureIncrementsFailedButNotSentForEmail() {
        GREEN_MAIL.stop();
        double beforeFailed = counter(FAILED_METRIC, "channel", "EMAIL");
        double beforeSent = counter(SENT_METRIC, "channel", "EMAIL");
        NotificationRequested event = NotificationRequested.of(
                "org-" + UUID.randomUUID(),
                "WELCOME",
                "metrics-down@example.com",
                null,
                null,
                Map.of("name", "Cam", "orgName", "Acme"));

        publisher.publish(event);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() ->
                        assertThat(counter(FAILED_METRIC, "channel", "EMAIL")).isEqualTo(beforeFailed + 1));
        assertThat(counter(SENT_METRIC, "channel", "EMAIL")).isEqualTo(beforeSent);
    }
}
