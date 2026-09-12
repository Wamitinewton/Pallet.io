package io.pallet.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.events.Topics;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import io.pallet.common.test.json.JsonTestSupport;
import io.pallet.identity.keycloak.KeycloakAdminTestConfiguration;
import io.pallet.identity.signup.SignupRequest;
import io.pallet.identity.token.InvalidTokenException;
import io.pallet.identity.verification.EmailVerificationService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({
    KeycloakTestContainerConfiguration.class,
    KeycloakAdminTestConfiguration.class,
    PasswordResetServiceIntegrationTest.EventCaptureConfiguration.class
})
class PasswordResetServiceIntegrationTest {

    private static final String SIGNUP_PASSWORD = "supersecretpassword";
    private static final String NEW_PASSWORD = "evenmoresecretpassword";
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private EmailVerificationService emailVerificationService;

    @Autowired
    private OneTimeActionTokenRepository tokenRepository;

    @Autowired
    private IdentityUserRepository identityUserRepository;

    @Autowired
    private EventCaptureConfiguration.CapturedEvents capturedEvents;

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String signUpAndVerify() throws Exception {
        String slug = "acme-" + unique();
        String email = "owner-" + unique() + "@pallet-test.local";
        mvc.perform(post("/api/v1/signup")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(
                                new SignupRequest("Acme " + slug, slug, email, "Ada Lovelace", SIGNUP_PASSWORD))))
                .andExpect(status().isCreated());
        emailVerificationService.verify(email, awaitAndExtractLatestVerificationCode(email));
        return email;
    }

    private List<ConsumerRecord<String, JsonNode>> notificationsFor(String notificationType, String email) {
        return capturedEvents.notificationRequested.stream()
                .filter(record -> notificationType.equals(
                                record.value().get("notificationType").asString())
                        && email.equals(record.value().get("recipient").asString()))
                .toList();
    }

    private String awaitAndExtractLatestVerificationCode(String email) {
        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(notificationsFor("EMAIL_VERIFICATION", email))
                        .hasSize(1));
        return notificationsFor("EMAIL_VERIFICATION", email)
                .getFirst()
                .value()
                .get("variables")
                .get("code")
                .asString();
    }

    private String awaitAndExtractResetToken(String email, int expectedCount) {
        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() ->
                        assertThat(notificationsFor("PASSWORD_RESET", email)).hasSize(expectedCount));
        List<ConsumerRecord<String, JsonNode>> requests = notificationsFor("PASSWORD_RESET", email);
        String resetUrl =
                requests.getLast().value().get("variables").get("resetUrl").asString();
        return resetUrl.substring(resetUrl.indexOf("token=") + "token=".length());
    }

    private boolean passwordGrantSucceeds(String email, String password) {
        return passwordGrant(email, password).status() == 200;
    }

    private TokenGrantResult passwordGrant(String email, String password) {
        String tokenEndpoint = KeycloakTestContainerConfiguration.serverUrl() + "/realms/"
                + KeycloakTestContainerConfiguration.realm() + "/protocol/openid-connect/token";
        String form =
                "grant_type=password&client_id=pallet-test-client&username=%s&password=%s".formatted(email, password);

        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            var response = HttpClient.newHttpClient().send(request, BodyHandlers.ofString());
            JsonNode body = JsonMapper.builder().build().readTree(response.body());
            return new TokenGrantResult(response.statusCode(), body);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to reach Keycloak token endpoint " + tokenEndpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Keycloak token endpoint", e);
        }
    }

    private record TokenGrantResult(int status, JsonNode body) {}

    @Test
    void requestResetForAnExistingEmailIssuesAToken() throws Exception {
        String email = signUpAndVerify();

        passwordResetService.requestReset(email);

        awaitAndExtractResetToken(email, 1);
        UUID userId = identityUserRepository.findByEmail(email).orElseThrow().getId();
        assertThat(tokenRepository.findAll())
                .anyMatch(
                        token -> token.getUserId().equals(userId) && token.getPurpose() == TokenPurpose.PASSWORD_RESET);
    }

    @Test
    void requestResetForANonExistentEmailIsANoOp() {
        String email = "nobody-" + unique() + "@pallet-test.local";

        passwordResetService.requestReset(email);

        assertThat(identityUserRepository.findByEmail(email)).isEmpty();
        assertThat(notificationsFor("PASSWORD_RESET", email)).isEmpty();
    }

    @Test
    void completeResetWithAFreshTokenUpdatesTheCredentialAndAudits() throws Exception {
        String email = signUpAndVerify();
        passwordResetService.requestReset(email);
        String rawToken = awaitAndExtractResetToken(email, 1);

        passwordResetService.completeReset(rawToken, NEW_PASSWORD);

        UUID userId = identityUserRepository.findByEmail(email).orElseThrow().getId();
        assertThat(tokenRepository.findAll())
                .filteredOn(token -> token.getUserId().equals(userId))
                .allSatisfy(token -> assertThat(token.getUsedAt()).isNotNull());

        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(capturedEvents.auditEvents)
                        .anyMatch(record -> "password-reset-completed"
                                .equals(record.value().get("action").asString())));

        assertThat(passwordGrantSucceeds(email, NEW_PASSWORD)).isTrue();
        assertThat(passwordGrantSucceeds(email, SIGNUP_PASSWORD)).isFalse();
    }

    @Test
    void completeResetWithAnAlreadyUsedTokenFails() throws Exception {
        String email = signUpAndVerify();
        passwordResetService.requestReset(email);
        String rawToken = awaitAndExtractResetToken(email, 1);
        passwordResetService.completeReset(rawToken, NEW_PASSWORD);

        assertThatThrownBy(() -> passwordResetService.completeReset(rawToken, "yetanotherpassword"))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void completeResetWithAnExpiredTokenFails() throws Exception {
        String email = signUpAndVerify();
        passwordResetService.requestReset(email);
        String rawToken = awaitAndExtractResetToken(email, 1);
        OneTimeActionToken active = tokenRepository.findAll().stream()
                .filter(token -> token.getUsedAt() == null)
                .findFirst()
                .orElseThrow();
        tokenRepository.delete(active);
        tokenRepository.save(new OneTimeActionToken(
                active.getUserId(),
                TokenPurpose.PASSWORD_RESET,
                active.getTokenHash(),
                Instant.now().minusSeconds(1)));

        assertThatThrownBy(() -> passwordResetService.completeReset(rawToken, NEW_PASSWORD))
                .isInstanceOf(InvalidTokenException.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EventCaptureConfiguration {

        @Bean
        CapturedEvents capturedEvents() {
            return new CapturedEvents();
        }

        static class CapturedEvents {

            final List<ConsumerRecord<String, JsonNode>> notificationRequested = new CopyOnWriteArrayList<>();
            final List<ConsumerRecord<String, JsonNode>> auditEvents = new CopyOnWriteArrayList<>();

            @KafkaListener(
                    topics = Topics.NOTIFICATION_REQUESTED,
                    groupId = "password-reset-test-notification-requested")
            void onNotificationRequested(ConsumerRecord<String, JsonNode> record) {
                notificationRequested.add(record);
            }

            @KafkaListener(topics = Topics.AUDIT_EVENT_RECORDED, groupId = "password-reset-test-audit-event")
            void onAuditEvent(ConsumerRecord<String, JsonNode> record) {
                auditEvents.add(record);
            }
        }
    }
}
