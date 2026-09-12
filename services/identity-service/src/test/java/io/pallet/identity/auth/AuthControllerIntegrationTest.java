package io.pallet.identity.auth;

import static io.pallet.common.test.assertions.PalletAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.pallet.common.error.ErrorResponse;
import io.pallet.common.events.Topics;
import io.pallet.common.resilience.ResilienceRegistries;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import io.pallet.common.test.json.JsonTestSupport;
import io.pallet.identity.keycloak.KeycloakAdminTestConfiguration;
import io.pallet.identity.signup.SignupRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
    AuthControllerIntegrationTest.EventCaptureConfiguration.class
})
class AuthControllerIntegrationTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final String PASSWORD = "supersecretpassword";
    private static final String KEYCLOAK_TOKEN_POLICY = "keycloak-token";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ResilienceRegistries resilienceRegistries;

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
                                new SignupRequest("Acme " + slug, slug, email, "Newton Kurunduuu", PASSWORD))))
                .andExpect(status().isCreated());
        return email;
    }

    private void verifyEmail(String email) throws Exception {
        String rawCode = awaitAndExtractCode(email);
        mvc.perform(post("/api/v1/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new VerifyEmailRequest(email, rawCode))))
                .andExpect(status().isOk());
    }

    private JsonNode login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();
        return JsonTestSupport.MAPPER
                .readTree(result.getResponse().getContentAsString())
                .get("data");
    }

    private long failedCallsWithoutRetryAttempt() {
        return resilienceRegistries
                .retry(KEYCLOAK_TOKEN_POLICY)
                .getMetrics()
                .getNumberOfFailedCallsWithoutRetryAttempt();
    }

    private long failedCallsWithRetryAttempt() {
        return resilienceRegistries.retry(KEYCLOAK_TOKEN_POLICY).getMetrics().getNumberOfFailedCallsWithRetryAttempt();
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
    void resendVerificationAlwaysReturns202ForARealAndAFakeEmail() throws Exception {
        String email = signUp();

        MvcResult realEmailResult = mvc.perform(post("/api/v1/auth/email/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new ResendVerificationRequest(email))))
                .andExpect(status().isAccepted())
                .andReturn();

        MvcResult fakeEmailResult = mvc.perform(post("/api/v1/auth/email/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(
                                new ResendVerificationRequest("nobody-" + unique() + "@pallet-test.local"))))
                .andExpect(status().isAccepted())
                .andReturn();

        assertThat(realEmailResult.getResponse().getContentAsString())
                .isEqualTo(fakeEmailResult.getResponse().getContentAsString());
    }

    @Test
    void verifyEmailWithAValidCodeSucceedsAndWithAnInvalidCodeFails() throws Exception {
        String email = signUp();
        String rawCode = awaitAndExtractCode(email);

        MvcResult invalidResult = mvc.perform(post("/api/v1/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new VerifyEmailRequest(email, "WRONGCOD"))))
                .andExpect(status().isBadRequest())
                .andReturn();
        ErrorResponse errorResponse =
                JsonTestSupport.fromJson(invalidResult.getResponse().getContentAsString(), ErrorResponse.class);
        assertThat(errorResponse).isFailure().hasErrorCode("INVALID_TOKEN");

        mvc.perform(post("/api/v1/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new VerifyEmailRequest(email, rawCode))))
                .andExpect(status().isOk());
    }

    @Test
    void validCredentialsAgainstAVerifiedAccountReturnTokensWithOrgIdAndRoleClaims() throws Exception {
        String email = signUp();
        verifyEmail(email);

        JsonNode tokens = login(email, PASSWORD);

        JWTClaimsSet claims =
                SignedJWT.parse(tokens.get("accessToken").asString()).getJWTClaimsSet();
        assertThat(claims.getStringClaim("org_id")).isNotBlank();
        @SuppressWarnings("unchecked")
        Map<String, Object> realmAccess = (Map<String, Object>) claims.getClaim("realm_access");
        assertThat((List<String>) realmAccess.get("roles")).contains("owner");
    }

    @Test
    void correctPasswordAgainstAnUnverifiedAccountIsEmailNotVerifiedWithoutRetry() throws Exception {
        String email = signUp();
        long before = failedCallsWithoutRetryAttempt();
        long beforeRetried = failedCallsWithRetryAttempt();

        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isForbidden())
                .andReturn();
        ErrorResponse errorResponse =
                JsonTestSupport.fromJson(result.getResponse().getContentAsString(), ErrorResponse.class);
        assertThat(errorResponse).isFailure().hasErrorCode("EMAIL_NOT_VERIFIED");

        assertThat(failedCallsWithoutRetryAttempt()).isEqualTo(before + 1);
        assertThat(failedCallsWithRetryAttempt()).isEqualTo(beforeRetried);
    }

    @Test
    void wrongPasswordIsUnauthorizedWithoutRetry() throws Exception {
        String email = signUp();
        verifyEmail(email);
        long before = failedCallsWithoutRetryAttempt();
        long beforeRetried = failedCallsWithRetryAttempt();

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new LoginRequest(email, "the-wrong-password"))))
                .andExpect(status().isUnauthorized());

        assertThat(failedCallsWithoutRetryAttempt()).isEqualTo(before + 1);
        assertThat(failedCallsWithRetryAttempt()).isEqualTo(beforeRetried);
    }

    @Test
    void validRefreshTokenReturnsANewTokenPair() throws Exception {
        String email = signUp();
        verifyEmail(email);
        String refreshToken = login(email, PASSWORD).get("refreshToken").asString();

        MvcResult result = mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new RefreshRequest(refreshToken))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode newTokens = JsonTestSupport.MAPPER
                .readTree(result.getResponse().getContentAsString())
                .get("data");
        assertThat(newTokens.get("accessToken").asString()).isNotBlank();
        assertThat(newTokens.get("refreshToken").asString()).isNotBlank();
    }

    @Test
    void logoutRevokesTheSessionSoTheSameRefreshTokenLaterFailsWithoutRetry() throws Exception {
        String email = signUp();
        verifyEmail(email);
        JsonNode tokens = login(email, PASSWORD);
        String accessToken = tokens.get("accessToken").asString();
        String refreshToken = tokens.get("refreshToken").asString();

        mvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new LogoutRequest(refreshToken))))
                .andExpect(status().isOk());

        long before = failedCallsWithoutRetryAttempt();
        long beforeRetried = failedCallsWithRetryAttempt();

        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new RefreshRequest(refreshToken))))
                .andExpect(status().isUnauthorized());

        assertThat(failedCallsWithoutRetryAttempt()).isEqualTo(before + 1);
        assertThat(failedCallsWithRetryAttempt()).isEqualTo(beforeRetried);
    }

    @Test
    void logoutWithoutABearerTokenIsUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new LogoutRequest("irrelevant"))))
                .andExpect(status().isUnauthorized());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EventCaptureConfiguration {

        @Bean
        CapturedEvents capturedEvents() {
            return new CapturedEvents();
        }

        static class CapturedEvents {

            final List<ConsumerRecord<String, JsonNode>> notificationRequested = new CopyOnWriteArrayList<>();

            @KafkaListener(topics = Topics.NOTIFICATION_REQUESTED, groupId = "auth-test-notification-requested")
            void onNotificationRequested(ConsumerRecord<String, JsonNode> record) {
                notificationRequested.add(record);
            }
        }
    }
}
