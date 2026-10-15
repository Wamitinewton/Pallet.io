package io.pallet.identity.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.pallet.common.events.Topics;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import io.pallet.common.test.json.JsonTestSupport;
import io.pallet.identity.config.InviteProperties;
import io.pallet.identity.keycloak.KeycloakAdminTestConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({
    KeycloakTestContainerConfiguration.class,
    KeycloakAdminTestConfiguration.class,
    ExistingAccountInviteAcceptIntegrationTest.EventCaptureConfiguration.class
})
class ExistingAccountInviteAcceptIntegrationTest {

    private static final String PASSWORD = "supersecretpassword";
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private InviteProperties inviteProperties;

    @Autowired
    private Keycloak keycloakAdminClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EventCaptureConfiguration.CapturedEvents capturedEvents;

    private record Account(String email, String keycloakUserId, String accessToken) {}

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String inviteToken(String orgId, String email, String jti) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .claim("purpose", "invite")
                    .claim("orgId", orgId)
                    .claim("email", email)
                    .claim("role", "viewer")
                    .jwtID(jti)
                    .expirationTime(Date.from(Instant.now().plus(Duration.ofMinutes(15))))
                    .build();
            SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            signedJwt.sign(new MACSigner(inviteProperties.signingKey().getBytes(StandardCharsets.UTF_8)));
            return signedJwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MockHttpServletRequestBuilder acceptRequest(String token, String password) {
        return post("/api/v1/identity/invites/{token}/accept", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonTestSupport.toJson(new InviteAcceptRequest(password)));
    }

    private ResultActions acceptAs(Account account, String token) throws Exception {
        return mvc.perform(acceptRequest(token, null).header("Authorization", "Bearer " + account.accessToken()));
    }

    /** An account founded through an invite into its own org, then signed in. */
    private Account existingAccount() throws Exception {
        String email = "member-" + unique() + "@pallet-test.local";
        mvc.perform(acceptRequest(
                        inviteToken("org-" + unique(), email, UUID.randomUUID().toString()), PASSWORD))
                .andExpect(status().isCreated());
        String accessToken = accessTokenFor(email);
        String sub = SignedJWT.parse(accessToken).getJWTClaimsSet().getSubject();
        return new Account(email, sub, accessToken);
    }

    private String accessTokenFor(String email) throws Exception {
        String tokenEndpoint = KeycloakTestContainerConfiguration.serverUrl() + "/realms/"
                + KeycloakTestContainerConfiguration.realm() + "/protocol/openid-connect/token";
        String form =
                "grant_type=password&client_id=pallet-test-client&username=%s&password=%s".formatted(email, PASSWORD);
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonMapper.builder()
                .build()
                .readTree(response.body())
                .get("access_token")
                .asString();
    }

    private int keycloakUserCount(String email) {
        return keycloakAdminClient
                .realm(KeycloakTestContainerConfiguration.realm())
                .users()
                .searchByEmail(email, true)
                .size();
    }

    private List<ConsumerRecord<String, JsonNode>> acceptedEventsFor(String orgId) {
        return capturedEvents.orgInviteAccepted.stream()
                .filter(record -> orgId.equals(record.value().get("orgId").asString()))
                .toList();
    }

    private boolean isConsumed(String jti) {
        return jdbcTemplate.queryForObject(
                        "select count(*) from identity.consumed_invite_tokens where jti = ?", Integer.class, jti)
                > 0;
    }

    @Test
    void anExistingAccountJoinsAnotherOrgWithItsOwnTokenAndNoNewKeycloakUser() throws Exception {
        Account account = existingAccount();
        String orgId = "org-" + unique();
        String jti = UUID.randomUUID().toString();

        acceptAs(account, inviteToken(orgId, account.email(), jti))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));

        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(acceptedEventsFor(orgId)).hasSize(1));
        JsonNode event = acceptedEventsFor(orgId).get(0).value();
        assertThat(event.get("userId").asString()).isEqualTo(account.keycloakUserId());
        assertThat(event.get("inviteId").asString()).isEqualTo(jti);
        assertThat(event.get("role").asString()).isEqualTo("viewer");

        assertThat(keycloakUserCount(account.email())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from identity.users where email = ?", Integer.class, account.email()))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "select created_user_id from identity.consumed_invite_tokens where jti = ?", String.class, jti))
                .isNull();
    }

    @Test
    void replayingTheSameInviteIsConflict() throws Exception {
        Account account = existingAccount();
        String token = inviteToken(
                "org-" + unique(), account.email(), UUID.randomUUID().toString());

        acceptAs(account, token).andExpect(status().isCreated());
        acceptAs(account, token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVITE_ALREADY_CONSUMED"));
    }

    @Test
    void aDifferentSignedInAccountIsForbiddenAndTheInviteStaysUsable() throws Exception {
        Account invited = existingAccount();
        Account someoneElse = existingAccount();
        String orgId = "org-" + unique();
        String jti = UUID.randomUUID().toString();
        String token = inviteToken(orgId, invited.email(), jti);

        acceptAs(someoneElse, token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INVITE_EMAIL_MISMATCH"));
        assertThat(isConsumed(jti)).isFalse();

        acceptAs(invited, token).andExpect(status().isCreated());
        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(acceptedEventsFor(orgId)).hasSize(1));
        assertThat(acceptedEventsFor(orgId).get(0).value().get("userId").asString())
                .isEqualTo(invited.keycloakUserId());
    }

    @Test
    void noTokenIsSignInRequiredEvenWithAPasswordAndNothingIsConsumed() throws Exception {
        Account account = existingAccount();
        String jti = UUID.randomUUID().toString();

        mvc.perform(acceptRequest(inviteToken("org-" + unique(), account.email(), jti), PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVITE_SIGN_IN_REQUIRED"));

        assertThat(isConsumed(jti)).isFalse();
        assertThat(keycloakUserCount(account.email())).isEqualTo(1);
    }

    @Test
    void theInvitedEmailMatchesTheAccountRegardlessOfCase() throws Exception {
        Account account = existingAccount();
        String orgId = "org-" + unique();

        acceptAs(
                        account,
                        inviteToken(
                                orgId,
                                account.email().toUpperCase(Locale.ROOT),
                                UUID.randomUUID().toString()))
                .andExpect(status().isCreated());

        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(acceptedEventsFor(orgId)).hasSize(1));
    }

    @Test
    void aNewAccountStillNeedsAPassword() throws Exception {
        String email = "invitee-" + unique() + "@pallet-test.local";

        mvc.perform(post(
                        "/api/v1/identity/invites/{token}/accept",
                        inviteToken("org-" + unique(), email, UUID.randomUUID().toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        assertThat(keycloakUserCount(email)).isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EventCaptureConfiguration {

        @Bean
        CapturedEvents capturedEvents() {
            return new CapturedEvents();
        }

        static class CapturedEvents {

            final List<ConsumerRecord<String, JsonNode>> orgInviteAccepted = new CopyOnWriteArrayList<>();

            @KafkaListener(topics = Topics.ORG_INVITE_ACCEPTED, groupId = "existing-account-invite-accept-test")
            void onOrgInviteAccepted(ConsumerRecord<String, JsonNode> record) {
                orgInviteAccepted.add(record);
            }
        }
    }
}
