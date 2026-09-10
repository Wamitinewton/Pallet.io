package io.pallet.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import io.pallet.notification.domain.Audience;
import io.pallet.notification.domain.Channel;
import io.pallet.notification.domain.Notification;
import io.pallet.notification.domain.NotificationDelivery;
import io.pallet.notification.repository.NotificationDeliveryRepository;
import io.pallet.notification.repository.NotificationRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Full-context slice against a real Postgres, exercising the security boundary with a mock JWT
 * ({@code SecurityMockMvcRequestPostProcessors.jwt()}) stamped with a chosen {@code sub}, the same
 * mechanism {@code PalletJwtRequestPostProcessors} uses for {@code org_id} — this test only needs
 * control over the subject claim, which that shared helper doesn't expose. {@code
 * KeycloakTestContainerConfiguration} is imported purely so a real {@code JwtDecoder} bean exists
 * for {@code PalletResourceServerAutoConfiguration}'s security filter chain to build at all; no
 * test here decodes an actual token against it.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(KeycloakTestContainerConfiguration.class)
class NotificationControllerIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    /**
     * No test in this class sends an email; this only exists so {@code EmailChannel}'s
     * {@code JavaMailSender} bean resolves during context startup.
     */
    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "localhost");
    }

    private static RequestPostProcessor userJwt(String userId) {
        return jwt().jwt(builder -> builder.subject(userId));
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

    /**
     * Every delivery gets its own {@link Notification}: {@code (notification_id, channel,
     * recipient)} is unique, so two deliveries on the same channel to the same recipient can't
     * share one notification row.
     */
    private NotificationDelivery persistDelivery(Channel channel, String recipient) {
        return deliveryRepository.saveAndFlush(
                new NotificationDelivery(persistNotification().getId(), channel, recipient));
    }

    @Test
    void anUnauthenticatedRequestIsRejected() throws Exception {
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
    }

    @Test
    void listReturnsTheApiResponseEnvelopeScopedToTheCaller() throws Exception {
        String userId = "user-" + UUID.randomUUID();
        persistDelivery(Channel.IN_APP, userId);
        persistDelivery(Channel.IN_APP, "someone-else-" + UUID.randomUUID());

        mvc.perform(get("/api/v1/notifications").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].title").value("Welcome!"));
    }

    @Test
    void sizeAboveMaxIsClampedToPageQueryMaxSize() throws Exception {
        String userId = "user-" + UUID.randomUUID();

        mvc.perform(get("/api/v1/notifications").param("size", "100000").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    @Test
    void getForAnotherRecipientsDeliveryReturns404() throws Exception {
        String owner = "user-" + UUID.randomUUID();
        String stranger = "user-" + UUID.randomUUID();
        NotificationDelivery delivery = persistDelivery(Channel.IN_APP, owner);

        mvc.perform(get("/api/v1/notifications/" + delivery.getId()).with(userJwt(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getForAnUnknownDeliveryIdReturns404() throws Exception {
        mvc.perform(get("/api/v1/notifications/" + UUID.randomUUID()).with(userJwt("user-" + UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void markReadOnAnAlreadyReadDeliveryIsIdempotent() throws Exception {
        String userId = "user-" + UUID.randomUUID();
        NotificationDelivery delivery = persistDelivery(Channel.IN_APP, userId);

        mvc.perform(patch("/api/v1/notifications/" + delivery.getId() + "/read").with(userJwt(userId)))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/notifications/" + delivery.getId() + "/read").with(userJwt(userId)))
                .andExpect(status().isOk());

        assertThat(deliveryRepository.findById(delivery.getId()).orElseThrow().getReadAt())
                .isNotNull();
    }

    @Test
    void readAllMarksEveryUnreadInAppDeliveryForTheCaller() throws Exception {
        String userId = "user-" + UUID.randomUUID();
        NotificationDelivery first = persistDelivery(Channel.IN_APP, userId);
        NotificationDelivery second = persistDelivery(Channel.IN_APP, userId);

        mvc.perform(post("/api/v1/notifications/read-all").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(deliveryRepository.findById(first.getId()).orElseThrow().getReadAt())
                .isNotNull();
        assertThat(deliveryRepository.findById(second.getId()).orElseThrow().getReadAt())
                .isNotNull();
    }

    @Test
    void unreadCountReflectsOnlyTheCallersUnreadInAppDeliveries() throws Exception {
        String userId = "user-" + UUID.randomUUID();
        persistDelivery(Channel.IN_APP, userId);
        NotificationDelivery read = persistDelivery(Channel.IN_APP, userId);
        read.markRead();
        deliveryRepository.saveAndFlush(read);
        persistDelivery(Channel.EMAIL, userId);

        mvc.perform(get("/api/v1/notifications/unread-count").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));
    }
}
