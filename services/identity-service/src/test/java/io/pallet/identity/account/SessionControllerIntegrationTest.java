package io.pallet.identity.account;

import static io.pallet.common.test.assertions.PalletAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jwt.SignedJWT;
import io.pallet.common.error.ErrorResponse;
import io.pallet.common.events.Topics;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
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
    RedisTestContainerConfiguration.class,
    SessionControllerIntegrationTest.EventCaptureConfiguration.class
})
class SessionControllerIntegrationTest {

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
        mvc.perform(post("/api/v1/identity/signup")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(
                                new SignupRequest("Acme " + slug, slug, email, "Newton Kurunduuu", PASSWORD))))
                .andExpect(status().isCreated());

        String rawCode = awaitAndExtractCode(email);
        mvc.perform(post("/api/v1/identity/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new VerifyEmailRequest(email, rawCode))))
                .andExpect(status().isOk());
        return email;
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

    private List<JsonNode> listSessions(String accessToken) throws Exception {
        MvcResult result = mvc.perform(
                        get("/api/v1/identity/users/me/sessions").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = JsonTestSupport.MAPPER
                .readTree(result.getResponse().getContentAsString())
                .get("data");
        return data.valueStream().toList();
    }

    @Test
    void listSessionsAfterTwoLoginsReturnsBothSessions() throws Exception {
        String email = signUpAndVerify();
        accessTokenFor(email, PASSWORD);
        String secondAccessToken = accessTokenFor(email, PASSWORD);

        List<JsonNode> sessions = listSessions(secondAccessToken);

        assertThat(sessions).hasSize(2);
    }

    @Test
    void deletingOwnSessionRevokesItAndItDisappearsFromASubsequentList() throws Exception {
        String email = signUpAndVerify();
        String firstAccessToken = accessTokenFor(email, PASSWORD);
        String secondAccessToken = accessTokenFor(email, PASSWORD);
        String firstSessionId = jwtSessionId(firstAccessToken);

        mvc.perform(delete("/api/v1/identity/users/me/sessions/" + firstSessionId)
                        .header("Authorization", "Bearer " + secondAccessToken))
                .andExpect(status().isOk());

        List<JsonNode> remaining = listSessions(secondAccessToken);
        assertThat(remaining).extracting(node -> node.get("id").asString()).doesNotContain(firstSessionId);
    }

    @Test
    void aRevokedSessionsAccessTokenIsRejectedOnItsNextRequestEvenThoughItHasNotExpiredYet() throws Exception {
        String email = signUpAndVerify();
        String revokedAccessToken = accessTokenFor(email, PASSWORD);
        String currentAccessToken = accessTokenFor(email, PASSWORD);
        String revokedSessionId = jwtSessionId(revokedAccessToken);

        mvc.perform(delete("/api/v1/identity/users/me/sessions/" + revokedSessionId)
                        .header("Authorization", "Bearer " + currentAccessToken))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/identity/users/me").header("Authorization", "Bearer " + revokedAccessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deletingAnotherUsersSessionIsNotFoundAndLeavesItActive() throws Exception {
        String ownEmail = signUpAndVerify();
        String ownAccessToken = accessTokenFor(ownEmail, PASSWORD);

        String otherEmail = signUpAndVerify();
        String otherAccessToken = accessTokenFor(otherEmail, PASSWORD);
        String otherSessionId = jwtSessionId(otherAccessToken);

        MvcResult result = mvc.perform(delete("/api/v1/identity/users/me/sessions/" + otherSessionId)
                        .header("Authorization", "Bearer " + ownAccessToken))
                .andExpect(status().isNotFound())
                .andReturn();
        ErrorResponse errorResponse =
                JsonTestSupport.fromJson(result.getResponse().getContentAsString(), ErrorResponse.class);
        assertThat(errorResponse).isFailure().hasErrorCode("SESSION_NOT_FOUND");

        assertThat(listSessions(otherAccessToken))
                .extracting(node -> node.get("id").asString())
                .contains(otherSessionId);
    }

    @Test
    void bulkRevokeRemovesEveryOtherSessionButKeepsTheCallingOneActive() throws Exception {
        String email = signUpAndVerify();
        accessTokenFor(email, PASSWORD);
        accessTokenFor(email, PASSWORD);
        String currentAccessToken = accessTokenFor(email, PASSWORD);
        String currentSessionId = jwtSessionId(currentAccessToken);

        mvc.perform(delete("/api/v1/identity/users/me/sessions")
                        .header("Authorization", "Bearer " + currentAccessToken))
                .andExpect(status().isOk());

        List<JsonNode> remaining = listSessions(currentAccessToken);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.getFirst().get("id").asString()).isEqualTo(currentSessionId);
    }

    private static String jwtSessionId(String accessToken) throws Exception {
        return SignedJWT.parse(accessToken).getJWTClaimsSet().getStringClaim("sid");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EventCaptureConfiguration {

        @Bean
        CapturedEvents capturedEvents() {
            return new CapturedEvents();
        }

        static class CapturedEvents {

            final List<ConsumerRecord<String, JsonNode>> notificationRequested = new CopyOnWriteArrayList<>();

            @KafkaListener(topics = Topics.NOTIFICATION_REQUESTED, groupId = "session-test-notification-requested")
            void onNotificationRequested(ConsumerRecord<String, JsonNode> record) {
                notificationRequested.add(record);
            }
        }
    }
}
