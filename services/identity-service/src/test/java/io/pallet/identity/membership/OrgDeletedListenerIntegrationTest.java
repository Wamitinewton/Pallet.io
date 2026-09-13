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
import io.pallet.common.events.OrgDeleted;
import io.pallet.common.messaging.MessagingProperties;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import io.pallet.common.test.json.JsonTestSupport;
import io.pallet.identity.account.IdentityUser;
import io.pallet.identity.account.IdentityUserRepository;
import io.pallet.identity.account.UserStatus;
import io.pallet.identity.config.InviteProperties;
import io.pallet.identity.invite.InviteAcceptRequest;
import io.pallet.identity.keycloak.KeycloakAdminTestConfiguration;
import io.pallet.identity.signup.SignupRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
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

@IntegrationTest
@AutoConfigureMockMvc
@Import({
    KeycloakTestContainerConfiguration.class,
    KeycloakAdminTestConfiguration.class,
    MembershipListenerCallCounter.class
})
class OrgDeletedListenerIntegrationTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final String PASSWORD = "supersecretpassword";
    private static final String MEMBER_ROLE = "admin";

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
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private MessagingProperties messagingProperties;

    @BeforeEach
    void ensureListenerHasSettled() {
        ContainerTestUtils.waitForAssignment(
                listenerRegistry.getListenerContainer("org-deleted-listener"), messagingProperties.topicPartitions());
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record Owner(String orgId, String email) {}

    private Owner seedOwner() throws Exception {
        String slug = "acme-" + unique();
        String email = "owner-" + unique() + "@pallet-test.local";
        var result = mvc.perform(post("/api/v1/signup")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(
                                new SignupRequest("Acme " + slug, slug, email, "Test Owner", PASSWORD))))
                .andExpect(status().isCreated())
                .andReturn();
        String orgId = JsonTestSupport.MAPPER
                .readTree(result.getResponse().getContentAsString())
                .get("data")
                .get("orgId")
                .asString();
        return new Owner(orgId, email);
    }

    private String seedMember(String orgId) throws Exception {
        String email = "member-" + unique() + "@pallet-test.local";
        String token = inviteToken(orgId, email);
        mvc.perform(post("/api/v1/invites/{token}/accept", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new InviteAcceptRequest(PASSWORD))))
                .andExpect(status().isCreated());
        return email;
    }

    private String inviteToken(String orgId, String email) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .claim("purpose", "invite")
                    .claim("orgId", orgId)
                    .claim("email", email)
                    .claim("role", MEMBER_ROLE)
                    .jwtID(UUID.randomUUID().toString())
                    .expirationTime(Date.from(Instant.now().plus(Duration.ofMinutes(15))))
                    .build();
            SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            signedJwt.sign(new MACSigner(inviteProperties.signingKey().getBytes(StandardCharsets.UTF_8)));
            return signedJwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Three members under one org: an owner from {@code /signup} and two invited members carrying
     * signed tokens for that same {@code orgId} — {@code org-team-service} doesn't exist yet, so
     * this mints its own tokens exactly as {@code InviteAcceptControllerIntegrationTest} does.
     */
    private List<String> seedThreeMembersUnderOneOrg() throws Exception {
        Owner owner = seedOwner();
        String member1Email = seedMember(owner.orgId());
        String member2Email = seedMember(owner.orgId());
        return List.of(owner.email(), member1Email, member2Email);
    }

    private boolean isEnabledInKeycloak(String keycloakUserId) {
        return keycloakAdminClient
                .realm(KeycloakTestContainerConfiguration.realm())
                .users()
                .get(keycloakUserId)
                .toRepresentation()
                .isEnabled();
    }

    private IdentityUser userFor(String email) {
        return identityUserRepository.findByEmail(email).orElseThrow();
    }

    @Test
    void orgDeletedDisablesEveryMemberInKeycloakAndLocally() throws Exception {
        List<String> emails = seedThreeMembersUnderOneOrg();
        String orgId = userFor(emails.get(0)).getOrgId();

        publisher.publish(OrgDeleted.of(orgId, userFor(emails.get(0)).getKeycloakUserId()));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            for (String email : emails) {
                assertThat(isEnabledInKeycloak(userFor(email).getKeycloakUserId()))
                        .isFalse();
                assertThat(userFor(email).getStatus()).isEqualTo(UserStatus.DISABLED);
            }
        });
    }

    @Test
    void redeliveryOfTheSameEventIsANoOpWithNoSecondRoundOfKeycloakCalls() throws Exception {
        List<String> emails = seedThreeMembersUnderOneOrg();
        String orgId = userFor(emails.get(0)).getOrgId();
        OrgDeleted event = OrgDeleted.of(orgId, userFor(emails.get(0)).getKeycloakUserId());

        publisher.publish(event);
        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(
                        () -> assertThat(userFor(emails.get(2)).getStatus()).isEqualTo(UserStatus.DISABLED));
        int callsAfterFirstDelivery = keycloakAdminCallCount.get();

        publisher.publish(event);
        await().pollDelay(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(keycloakAdminCallCount.get()).isEqualTo(callsAfterFirstDelivery));
    }

    @Test
    void unknownOrgIsLoggedAsANoOpAndDoesNotBlockLaterMessages() throws Exception {
        publisher.publish(OrgDeleted.of("org-" + unique(), "nonexistent-" + unique()));

        List<String> emails = seedThreeMembersUnderOneOrg();
        String orgId = userFor(emails.get(0)).getOrgId();
        publisher.publish(OrgDeleted.of(orgId, userFor(emails.get(0)).getKeycloakUserId()));

        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(
                        () -> assertThat(userFor(emails.get(2)).getStatus()).isEqualTo(UserStatus.DISABLED));
    }
}
