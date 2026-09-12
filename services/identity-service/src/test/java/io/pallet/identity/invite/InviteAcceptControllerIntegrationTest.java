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
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

@IntegrationTest
@AutoConfigureMockMvc
@Import({
    KeycloakTestContainerConfiguration.class,
    KeycloakAdminTestConfiguration.class,
    InviteAcceptControllerIntegrationTest.EventCaptureConfiguration.class
})
class InviteAcceptControllerIntegrationTest {

    private static final String ACCEPT_PASSWORD = "supersecretpassword";
    private static final String ROLE = "admin";
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

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String inviteToken(String orgId, String email, String role, String jti, Duration ttl, String secret) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .claim("purpose", "invite")
                    .claim("orgId", orgId)
                    .claim("email", email)
                    .claim("role", role)
                    .jwtID(jti)
                    .expirationTime(Date.from(Instant.now().plus(ttl)))
                    .build();
            SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            signedJwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return signedJwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String inviteToken(String orgId, String email) {
        return inviteToken(
                orgId,
                email,
                ROLE,
                UUID.randomUUID().toString(),
                Duration.ofMinutes(15),
                inviteProperties.signingKey());
    }

    private ResultActions performAccept(String token) throws Exception {
        return mvc.perform(post("/api/v1/invites/{token}/accept", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonTestSupport.toJson(new InviteAcceptRequest(ACCEPT_PASSWORD))));
    }

    private List<UserRepresentation> keycloakUsersByEmail(String email) {
        return keycloakAdminClient
                .realm(KeycloakTestContainerConfiguration.realm())
                .users()
                .searchByEmail(email, true);
    }

    private boolean passwordGrantSucceeds(String email) {
        String tokenEndpoint = KeycloakTestContainerConfiguration.serverUrl() + "/realms/"
                + KeycloakTestContainerConfiguration.realm() + "/protocol/openid-connect/token";
        String form = "grant_type=password&client_id=pallet-test-client&username=%s&password=%s"
                .formatted(email, ACCEPT_PASSWORD);

        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            var response = HttpClient.newHttpClient().send(request, BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to reach Keycloak token endpoint " + tokenEndpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Keycloak token endpoint", e);
        }
    }

    @Test
    void validTokenCreatesAVerifiedAccountAndPublishesEventsExactlyOnce() throws Exception {
        String orgId = "org-" + unique();
        String email = "invitee-" + unique() + "@pallet-test.local";

        performAccept(inviteToken(orgId, email))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));

        List<UserRepresentation> keycloakUsers = keycloakUsersByEmail(email);
        assertThat(keycloakUsers).hasSize(1);
        UserRepresentation keycloakUser = keycloakUsers.get(0);
        assertThat(keycloakUser.isEmailVerified()).isTrue();
        assertThat(keycloakUser.getRequiredActions()).isEmpty();
        assertThat(keycloakUser.getAttributes()).containsEntry("org_id", List.of(orgId));

        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from identity.users where email = ? and org_id = ?",
                        Integer.class,
                        email,
                        orgId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from identity.consumed_invite_tokens", Integer.class))
                .isGreaterThanOrEqualTo(1);

        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(capturedEvents.orgInviteAccepted.stream()
                                .filter(record ->
                                        email.equals(record.value().get("email").asString()))
                                .count())
                        .isEqualTo(1));
        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(capturedEvents.auditEvents.stream()
                                .filter(record -> "invite-accepted"
                                        .equals(record.value().get("action").asString()))
                                .count())
                        .isEqualTo(1));

        assertThat(passwordGrantSucceeds(email)).isTrue();
    }

    @Test
    void replayingAnAlreadyAcceptedTokenIsConflict() throws Exception {
        String orgId = "org-" + unique();
        String email = "invitee-" + unique() + "@pallet-test.local";
        String token = inviteToken(orgId, email);

        performAccept(token).andExpect(status().isCreated());
        performAccept(token).andExpect(status().isConflict());

        assertThat(keycloakUsersByEmail(email)).hasSize(1);
    }

    @Test
    void expiredTokenIsRejectedWithoutCreatingAKeycloakUser() throws Exception {
        String orgId = "org-" + unique();
        String email = "invitee-" + unique() + "@pallet-test.local";
        String token = inviteToken(
                orgId,
                email,
                ROLE,
                UUID.randomUUID().toString(),
                Duration.ofMinutes(-1),
                inviteProperties.signingKey());

        performAccept(token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));

        assertThat(keycloakUsersByEmail(email)).isEmpty();
    }

    @Test
    void tokenSignedWithTheWrongSecretIsRejected() throws Exception {
        String orgId = "org-" + unique();
        String email = "invitee-" + unique() + "@pallet-test.local";
        String token = inviteToken(
                orgId,
                email,
                ROLE,
                UUID.randomUUID().toString(),
                Duration.ofMinutes(15),
                "a-completely-different-32-byte-secret!!");

        performAccept(token).andExpect(status().isBadRequest());

        assertThat(keycloakUsersByEmail(email)).isEmpty();
    }

    @Test
    void tokenWithTheWrongPurposeIsRejected() throws Exception {
        String orgId = "org-" + unique();
        String email = "invitee-" + unique() + "@pallet-test.local";
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("purpose", "password-reset")
                .claim("orgId", orgId)
                .claim("email", email)
                .claim("role", ROLE)
                .jwtID(UUID.randomUUID().toString())
                .expirationTime(Date.from(Instant.now().plus(Duration.ofMinutes(15))))
                .build();
        SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        signedJwt.sign(new MACSigner(inviteProperties.signingKey().getBytes(StandardCharsets.UTF_8)));

        performAccept(signedJwt.serialize()).andExpect(status().isBadRequest());

        assertThat(keycloakUsersByEmail(email)).isEmpty();
    }

    @Test
    void concurrentAcceptsOfTheSameTokenLeaveExactlyOneAccountAndNoOrphan() throws Exception {
        String orgId = "org-" + unique();
        String email = "invitee-" + unique() + "@pallet-test.local";
        String token = inviteToken(orgId, email);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = List.of(
                    executor.submit(() -> raceAccept(token, startLatch)),
                    executor.submit(() -> raceAccept(token, startLatch)));
            startLatch.countDown();

            List<Integer> statuses = results.stream().map(this::join).toList();
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        } finally {
            executor.shutdown();
        }

        assertThat(keycloakUsersByEmail(email)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from identity.users where email = ?", Integer.class, email))
                .isEqualTo(1);
    }

    private int raceAccept(String token, CountDownLatch startLatch) {
        try {
            startLatch.await();
            return performAccept(token).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T join(Future<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EventCaptureConfiguration {

        @Bean
        CapturedEvents capturedEvents() {
            return new CapturedEvents();
        }

        static class CapturedEvents {

            final List<ConsumerRecord<String, JsonNode>> orgInviteAccepted = new CopyOnWriteArrayList<>();
            final List<ConsumerRecord<String, JsonNode>> auditEvents = new CopyOnWriteArrayList<>();

            @KafkaListener(topics = Topics.ORG_INVITE_ACCEPTED, groupId = "invite-accept-test-org-invite-accepted")
            void onOrgInviteAccepted(ConsumerRecord<String, JsonNode> record) {
                orgInviteAccepted.add(record);
            }

            @KafkaListener(topics = Topics.AUDIT_EVENT_RECORDED, groupId = "invite-accept-test-audit-event")
            void onAuditEvent(ConsumerRecord<String, JsonNode> record) {
                auditEvents.add(record);
            }
        }
    }
}
