package io.pallet.identity.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.events.OrgInviteRejected;
import io.pallet.common.messaging.MessagingProperties;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.security.RevokedSessionRegistry;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.common.test.json.JsonTestSupport;
import io.pallet.identity.account.IdentityUserRepository;
import io.pallet.identity.account.UserStatus;
import io.pallet.identity.config.InviteProperties;
import io.pallet.identity.invite.InviteAcceptRequest;
import io.pallet.identity.keycloak.KeycloakAdminTestConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({
    KeycloakTestContainerConfiguration.class,
    KeycloakAdminTestConfiguration.class,
    MembershipListenerCallCounter.class,
    RedisTestContainerConfiguration.class
})
class OrgInviteRejectedListenerIntegrationTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final String PASSWORD = "supersecretpassword";
    private static final String NOOP_METRIC = "identity.membership_sync.noop";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private Keycloak keycloakAdminClient;

    @Autowired
    private IdentityUserRepository identityUserRepository;

    @Autowired
    private InviteProperties inviteProperties;

    @Autowired
    private AtomicInteger keycloakAdminCallCount;

    @Autowired
    private RevokedSessionRegistry revokedSessionRegistry;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private MessagingProperties messagingProperties;

    @BeforeEach
    void ensureListenerHasSettled() {
        ContainerTestUtils.waitForAssignment(
                listenerRegistry.getListenerContainer("org-invite-rejected-listener"),
                messagingProperties.topicPartitions());
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record InvitedAccount(String orgId, String inviteId, String keycloakUserId, String email) {}

    private InvitedAccount acceptInvite() throws Exception {
        String orgId = "org-" + unique();
        String email = "invitee-" + unique() + "@pallet-test.local";
        String inviteId = UUID.randomUUID().toString();

        mvc.perform(post("/api/v1/identity/invites/{token}/accept", inviteToken(orgId, email, inviteId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new InviteAcceptRequest(PASSWORD))))
                .andExpect(status().isCreated());

        String keycloakUserId = keycloakAdminClient
                .realm(KeycloakTestContainerConfiguration.realm())
                .users()
                .searchByEmail(email, true)
                .get(0)
                .getId();
        return new InvitedAccount(orgId, inviteId, keycloakUserId, email);
    }

    private String inviteToken(String orgId, String email, String inviteId) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .claim("purpose", "invite")
                    .claim("orgId", orgId)
                    .claim("email", email)
                    .claim("role", "admin")
                    .jwtID(inviteId)
                    .expirationTime(Date.from(Instant.now().plus(Duration.ofMinutes(15))))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(inviteProperties.signingKey().getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean isEnabledInKeycloak(String keycloakUserId) {
        return keycloakAdminClient
                .realm(KeycloakTestContainerConfiguration.realm())
                .users()
                .get(keycloakUserId)
                .toRepresentation()
                .isEnabled();
    }

    private HttpResponse<String> passwordGrant(String email) throws Exception {
        String tokenEndpoint = KeycloakTestContainerConfiguration.serverUrl() + "/realms/"
                + KeycloakTestContainerConfiguration.realm() + "/protocol/openid-connect/token";
        String form =
                "grant_type=password&client_id=pallet-test-client&username=%s&password=%s".formatted(email, PASSWORD);
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private double noopCount() {
        return meterRegistry.counter(NOOP_METRIC, "event", "OrgInviteRejected").count();
    }

    @Test
    void rejectedInviteDisablesTheAccountKeycloakSideAndLocally() throws Exception {
        InvitedAccount account = acceptInvite();
        assertThat(passwordGrant(account.email()).statusCode()).isEqualTo(200);

        publisher.publish(OrgInviteRejected.of(
                account.orgId(),
                account.inviteId(),
                account.keycloakUserId(),
                account.email(),
                OrgInviteRejected.REASON_REVOKED));

        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(isEnabledInKeycloak(account.keycloakUserId()))
                        .isFalse());
        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(identityUserRepository
                                .findByKeycloakUserId(account.keycloakUserId())
                                .orElseThrow()
                                .getStatus())
                        .isEqualTo(UserStatus.DISABLED));
        assertThat(passwordGrant(account.email()).statusCode()).isNotEqualTo(200);
    }

    @Test
    void existingSessionIsRevokedInTheRegistry() throws Exception {
        InvitedAccount account = acceptInvite();
        HttpResponse<String> login = passwordGrant(account.email());
        String accessToken = JsonMapper.builder()
                .build()
                .readTree(login.body())
                .get("access_token")
                .asString();
        String sessionId = SignedJWT.parse(accessToken).getJWTClaimsSet().getStringClaim("sid");

        publisher.publish(OrgInviteRejected.of(
                account.orgId(),
                account.inviteId(),
                account.keycloakUserId(),
                account.email(),
                OrgInviteRejected.REASON_EXPIRED));

        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() ->
                        assertThat(revokedSessionRegistry.isRevoked(sessionId)).isTrue());
    }

    @Test
    void redeliveryOfTheSameEventMakesNoSecondKeycloakCall() throws Exception {
        InvitedAccount account = acceptInvite();
        OrgInviteRejected event = OrgInviteRejected.of(
                account.orgId(),
                account.inviteId(),
                account.keycloakUserId(),
                account.email(),
                OrgInviteRejected.REASON_REVOKED);

        publisher.publish(event);
        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(isEnabledInKeycloak(account.keycloakUserId()))
                        .isFalse());
        int callsAfterFirstDelivery = keycloakAdminCallCount.get();

        publisher.publish(event);
        await().pollDelay(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(keycloakAdminCallCount.get()).isEqualTo(callsAfterFirstDelivery));
    }

    @Test
    void orgMismatchLeavesTheAccountUntouchedAndCountsANoOp() throws Exception {
        InvitedAccount account = acceptInvite();
        double before = noopCount();

        publisher.publish(OrgInviteRejected.of(
                "org-" + unique(),
                account.inviteId(),
                account.keycloakUserId(),
                account.email(),
                OrgInviteRejected.REASON_REVOKED));

        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(noopCount()).isGreaterThan(before));
        assertThat(isEnabledInKeycloak(account.keycloakUserId())).isTrue();
        assertThat(identityUserRepository
                        .findByKeycloakUserId(account.keycloakUserId())
                        .orElseThrow()
                        .getStatus())
                .isNotEqualTo(UserStatus.DISABLED);
    }

    @Test
    void unknownUserIsANoOpAndDoesNotBlockLaterMessages() throws Exception {
        double before = noopCount();
        publisher.publish(OrgInviteRejected.of(
                "org-" + unique(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "ghost@pallet-test.local",
                OrgInviteRejected.REASON_UNKNOWN_INVITE));
        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(noopCount()).isGreaterThan(before));

        InvitedAccount account = acceptInvite();
        publisher.publish(OrgInviteRejected.of(
                account.orgId(),
                account.inviteId(),
                account.keycloakUserId(),
                account.email(),
                OrgInviteRejected.REASON_ORG_DELETED));

        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(isEnabledInKeycloak(account.keycloakUserId()))
                        .isFalse());
    }
}
