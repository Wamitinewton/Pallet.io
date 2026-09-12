package io.pallet.identity.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.events.Topics;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import io.pallet.common.test.json.JsonTestSupport;
import io.pallet.identity.account.IdentityUser;
import io.pallet.identity.account.IdentityUserRepository;
import io.pallet.identity.keycloak.KeycloakAdminTestConfiguration;
import io.pallet.identity.signup.SignupRequest;
import io.pallet.identity.token.InvalidTokenException;
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
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.UserRepresentation;
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
    EmailVerificationServiceIntegrationTest.EventCaptureConfiguration.class
})
class EmailVerificationServiceIntegrationTest {

    private static final String SIGNUP_PASSWORD = "supersecretpassword";
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EmailVerificationService emailVerificationService;

    @Autowired
    private EmailVerificationCodeRepository codeRepository;

    @Autowired
    private IdentityUserRepository identityUserRepository;

    @Autowired
    private Keycloak keycloakAdminClient;

    @Autowired
    private EventCaptureConfiguration.CapturedEvents capturedEvents;

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String signUp() throws Exception {
        String slug = "acme-" + unique();
        String email = "owner-" + unique() + "@pallet-test.local";
        mvc.perform(post("/api/v1/signup")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(
                                new SignupRequest("Acme " + slug, slug, email, "Ada Lovelace", SIGNUP_PASSWORD))))
                .andExpect(status().isCreated());
        return email;
    }

    private IdentityUser userByEmail(String email) {
        return identityUserRepository.findByEmail(email).orElseThrow();
    }

    private List<ConsumerRecord<String, JsonNode>> emailVerificationRequestsFor(String email) {
        return capturedEvents.notificationRequested.stream()
                .filter(record -> "EMAIL_VERIFICATION"
                                .equals(record.value().get("notificationType").asString())
                        && email.equals(record.value().get("recipient").asString()))
                .toList();
    }

    private String awaitAndExtractLatestCode(String email, int expectedCount) {
        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(
                        () -> assertThat(emailVerificationRequestsFor(email)).hasSize(expectedCount));
        List<ConsumerRecord<String, JsonNode>> requests = emailVerificationRequestsFor(email);
        return requests.get(requests.size() - 1)
                .value()
                .get("variables")
                .get("code")
                .asString();
    }

    private boolean tokenGrantSucceeds(String email) {
        return passwordGrant(email).status() == 200;
    }

    private String tokenGrantErrorDescription(String email) {
        TokenGrantResult result = passwordGrant(email);
        return result.body().path("error_description").asString("");
    }

    private TokenGrantResult passwordGrant(String email) {
        String tokenEndpoint = KeycloakTestContainerConfiguration.serverUrl() + "/realms/"
                + KeycloakTestContainerConfiguration.realm() + "/protocol/openid-connect/token";
        String form = "grant_type=password&client_id=pallet-test-client&username=%s&password=%s"
                .formatted(email, SIGNUP_PASSWORD);

        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            var response = HttpClient.newHttpClient().send(request, BodyHandlers.ofString());
            JsonNode body = JsonMapper.builder().build().readTree(response.body());
            return new TokenGrantResult(response.statusCode(), body);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to reach Keycloak token endpoint " + tokenEndpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Keycloak token endpoint", e);
        }
    }

    private record TokenGrantResult(int status, JsonNode body) {}

    @Test
    void freshSignUpIssuesACodeAndBlocksAuthenticationUntilVerified() throws Exception {
        String email = signUp();
        IdentityUser user = userByEmail(email);

        assertThat(codeRepository.findByUserIdAndConsumedAtIsNull(user.getId())).isPresent();
        awaitAndExtractLatestCode(email, 1);

        assertThat(tokenGrantErrorDescription(email)).contains("not fully set up");
    }

    @Test
    void resendForAnExistingUnverifiedEmailReplacesTheCode() throws Exception {
        String email = signUp();
        IdentityUser user = userByEmail(email);
        awaitAndExtractLatestCode(email, 1);
        UUID firstCodeId = codeRepository
                .findByUserIdAndConsumedAtIsNull(user.getId())
                .orElseThrow()
                .getId();

        emailVerificationService.resend(email);

        awaitAndExtractLatestCode(email, 2);
        EmailVerificationCode current =
                codeRepository.findByUserIdAndConsumedAtIsNull(user.getId()).orElseThrow();
        assertThat(current.getId()).isNotEqualTo(firstCodeId);
    }

    @Test
    void resendForANonExistentEmailIsANoOp() {
        String email = "nobody-" + unique() + "@pallet-test.local";

        emailVerificationService.resend(email);

        assertThat(identityUserRepository.findByEmail(email)).isEmpty();
        assertThat(emailVerificationRequestsFor(email)).isEmpty();
    }

    @Test
    void verifyWithTheCorrectCodeUnlocksAuthentication() throws Exception {
        String email = signUp();
        String rawCode = awaitAndExtractLatestCode(email, 1);

        emailVerificationService.verify(email, rawCode);

        IdentityUser user = userByEmail(email);
        UserRepresentation keycloakUser = keycloakAdminClient
                .realm(KeycloakTestContainerConfiguration.realm())
                .users()
                .get(user.getKeycloakUserId())
                .toRepresentation();
        assertThat(keycloakUser.isEmailVerified()).isTrue();
        assertThat(keycloakUser.getRequiredActions()).isEmpty();

        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(capturedEvents.auditEvents)
                        .anyMatch(record -> "email-verified"
                                .equals(record.value().get("action").asString())));

        assertThat(tokenGrantSucceeds(email)).isTrue();
    }

    @Test
    void verifyWithAWrongCodeIncrementsAttemptsAndStaysUsable() throws Exception {
        String email = signUp();
        awaitAndExtractLatestCode(email, 1);
        IdentityUser user = userByEmail(email);

        assertThatThrownBy(() -> emailVerificationService.verify(email, "WRONGCOD"))
                .isInstanceOf(InvalidTokenException.class);

        EmailVerificationCode code =
                codeRepository.findByUserIdAndConsumedAtIsNull(user.getId()).orElseThrow();
        assertThat(code.getAttempts()).isEqualTo(1);
    }

    @Test
    void verifyPastMaxAttemptsInvalidatesTheCodeUntilAFreshResend() throws Exception {
        String email = signUp();
        awaitAndExtractLatestCode(email, 1);
        IdentityUser user = userByEmail(email);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> emailVerificationService.verify(email, "WRONGCOD"))
                    .isInstanceOf(InvalidTokenException.class);
        }
        assertThat(codeRepository
                        .findByUserIdAndConsumedAtIsNull(user.getId())
                        .orElseThrow()
                        .getAttempts())
                .isEqualTo(5);

        assertThatThrownBy(() -> emailVerificationService.verify(email, "STILLBAD"))
                .isInstanceOf(InvalidTokenException.class);
        assertThat(codeRepository.findByUserIdAndConsumedAtIsNull(user.getId())).isEmpty();

        emailVerificationService.resend(email);
        String freshCode = awaitAndExtractLatestCode(email, 2);
        emailVerificationService.verify(email, freshCode);
        assertThat(tokenGrantSucceeds(email)).isTrue();
    }

    @Test
    void verifyWithAnExpiredCodeFails() throws Exception {
        String email = signUp();
        awaitAndExtractLatestCode(email, 1);
        IdentityUser user = userByEmail(email);
        EmailVerificationCode active =
                codeRepository.findByUserIdAndConsumedAtIsNull(user.getId()).orElseThrow();
        codeRepository.delete(active);
        codeRepository.save(new EmailVerificationCode(
                user.getId(), active.getCodeHash(), Instant.now().minusSeconds(1)));

        assertThatThrownBy(() -> emailVerificationService.verify(email, "ANYCODE1"))
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
                    groupId = "email-verification-test-notification-requested")
            void onNotificationRequested(ConsumerRecord<String, JsonNode> record) {
                notificationRequested.add(record);
            }

            @KafkaListener(topics = Topics.AUDIT_EVENT_RECORDED, groupId = "email-verification-test-audit-event")
            void onAuditEvent(ConsumerRecord<String, JsonNode> record) {
                auditEvents.add(record);
            }
        }
    }
}
