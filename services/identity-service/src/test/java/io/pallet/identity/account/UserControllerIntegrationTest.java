package io.pallet.identity.account;

import static io.pallet.common.test.assertions.PalletAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jwt.SignedJWT;
import io.pallet.common.error.ErrorResponse;
import io.pallet.common.events.Topics;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import io.pallet.common.test.json.JsonTestSupport;
import io.pallet.identity.auth.VerifyEmailRequest;
import io.pallet.identity.keycloak.KeycloakAdminTestConfiguration;
import io.pallet.identity.signup.SignupRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

@IntegrationTest
@AutoConfigureMockMvc
@Import({
    KeycloakTestContainerConfiguration.class,
    KeycloakAdminTestConfiguration.class,
    UserControllerIntegrationTest.EventCaptureConfiguration.class
})
class UserControllerIntegrationTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final String PASSWORD = "supersecretpassword";

    @Autowired
    private MockMvc mvc;

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
                                new SignupRequest("Acme " + slug, slug, email, "Newton Kurunduuu", PASSWORD))))
                .andExpect(status().isCreated());

        String rawCode = awaitAndExtractCode(email);
        mvc.perform(post("/api/v1/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new VerifyEmailRequest(email, rawCode))))
                .andExpect(status().isOk());
        return email;
    }

    private HttpResponse<String> passwordGrant(String email, String password) throws Exception {
        String tokenEndpoint = KeycloakTestContainerConfiguration.serverUrl() + "/realms/"
                + KeycloakTestContainerConfiguration.realm() + "/protocol/openid-connect/token";
        String form =
                "grant_type=password&client_id=pallet-test-client&username=%s&password=%s".formatted(email, password);
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String accessTokenFor(String email, String password) throws Exception {
        HttpResponse<String> response = passwordGrant(email, password);
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonTestSupport.MAPPER
                .readTree(response.body())
                .get("access_token")
                .asString();
    }

    private String awaitAndExtractCode(String email) {
        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(capturedEvents.notificationRequested.stream()
                                .anyMatch(record -> "EMAIL_VERIFICATION"
                                                .equals(record.value()
                                                        .get("notificationType")
                                                        .asString())
                                        && email.equals(
                                                record.value().get("recipient").asString())))
                        .isTrue());
        return capturedEvents.notificationRequested.stream()
                .filter(record -> "EMAIL_VERIFICATION"
                                .equals(record.value().get("notificationType").asString())
                        && email.equals(record.value().get("recipient").asString()))
                .reduce((first, second) -> second)
                .orElseThrow()
                .value()
                .get("variables")
                .get("code")
                .asString();
    }

    @Test
    void meWithAValidTokenReturnsTokenClaimsAndLocalProfile() throws Exception {
        String email = signUpAndVerify();
        String accessToken = accessTokenFor(email, PASSWORD);
        String sub = SignedJWT.parse(accessToken).getJWTClaimsSet().getSubject();

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpectAll(
                        jsonPath("$.data.sub").value(sub),
                        jsonPath("$.data.email").value(email),
                        jsonPath("$.data.displayName").value("Newton Kurunduuu"),
                        jsonPath("$.data.status").value("ACTIVE"),
                        jsonPath("$.data.roles").isArray());
    }

    @Test
    void meWithoutATokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void updateProfileChangesDisplayNameAndPublishesUserProfileUpdatedOnce() throws Exception {
        String email = signUpAndVerify();
        String accessToken = accessTokenFor(email, PASSWORD);

        mvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new UpdateProfileRequest("New Display Name"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("New Display Name"));

        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(capturedEvents.userProfileUpdated.stream()
                                .filter(record -> "New Display Name"
                                        .equals(record.value()
                                                .get("displayName")
                                                .asString()))
                                .count())
                        .isEqualTo(1));
    }

    @Test
    void updateProfileIgnoresAnEmailFieldOnTheRequestBody() throws Exception {
        String email = signUpAndVerify();
        String accessToken = accessTokenFor(email, PASSWORD);

        mvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Still Updated","email":"someone-else@pallet-test.local"}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email));
    }

    @Test
    void changePasswordWithCorrectCurrentPasswordAllowsLoginWithTheNewPasswordAndPublishesAudit() throws Exception {
        String email = signUpAndVerify();
        String accessToken = accessTokenFor(email, PASSWORD);
        String newPassword = "brandnewpassword123";

        mvc.perform(post("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new ChangePasswordRequest(PASSWORD, newPassword))))
                .andExpect(status().isOk());

        accessTokenFor(email, newPassword);
        assertThat(passwordGrant(email, PASSWORD).statusCode()).isEqualTo(401);

        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(capturedEvents.auditEvents.stream()
                                .filter(record -> "password-changed"
                                        .equals(record.value().get("action").asString()))
                                .count())
                        .isEqualTo(1));
    }

    @Test
    void changePasswordWithAnIncorrectCurrentPasswordIsUnauthorizedAndLeavesCredentialUnchanged() throws Exception {
        String email = signUpAndVerify();
        String accessToken = accessTokenFor(email, PASSWORD);

        MvcResult result = mvc.perform(post("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(
                                new ChangePasswordRequest("the-wrong-password", "irrelevant1234"))))
                .andExpect(status().isUnauthorized())
                .andReturn();
        ErrorResponse errorResponse =
                JsonTestSupport.fromJson(result.getResponse().getContentAsString(), ErrorResponse.class);
        assertThat(errorResponse).isFailure().hasErrorCode("INVALID_CREDENTIALS");

        accessTokenFor(email, PASSWORD);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EventCaptureConfiguration {

        @Bean
        CapturedEvents capturedEvents() {
            return new CapturedEvents();
        }

        static class CapturedEvents {

            final List<ConsumerRecord<String, JsonNode>> notificationRequested = new CopyOnWriteArrayList<>();
            final List<ConsumerRecord<String, JsonNode>> userProfileUpdated = new CopyOnWriteArrayList<>();
            final List<ConsumerRecord<String, JsonNode>> auditEvents = new CopyOnWriteArrayList<>();

            @KafkaListener(topics = Topics.NOTIFICATION_REQUESTED, groupId = "user-test-notification-requested")
            void onNotificationRequested(ConsumerRecord<String, JsonNode> record) {
                notificationRequested.add(record);
            }

            @KafkaListener(topics = Topics.USER_PROFILE_UPDATED, groupId = "user-test-user-profile-updated")
            void onUserProfileUpdated(ConsumerRecord<String, JsonNode> record) {
                userProfileUpdated.add(record);
            }

            @KafkaListener(topics = Topics.AUDIT_EVENT_RECORDED, groupId = "user-test-audit-event")
            void onAuditEvent(ConsumerRecord<String, JsonNode> record) {
                auditEvents.add(record);
            }
        }
    }
}
