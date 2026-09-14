package io.pallet.identity.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.events.OrgMemberRemoved;
import io.pallet.common.messaging.MessagingProperties;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import io.pallet.common.test.json.JsonTestSupport;
import io.pallet.identity.account.IdentityUserRepository;
import io.pallet.identity.account.UserStatus;
import io.pallet.identity.keycloak.KeycloakAdminTestConfiguration;
import io.pallet.identity.signup.SignupRequest;
import java.time.Duration;
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
class OrgMemberRemovedListenerIntegrationTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final String PASSWORD = "supersecretpassword";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private Keycloak keycloakAdminClient;

    @Autowired
    private IdentityUserRepository identityUserRepository;

    @Autowired
    private AtomicInteger keycloakAdminCallCount;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private MessagingProperties messagingProperties;

    @BeforeEach
    void ensureListenerHasSettled() {
        ContainerTestUtils.waitForAssignment(
                listenerRegistry.getListenerContainer("org-member-removed-listener"),
                messagingProperties.topicPartitions());
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record SeededUser(String orgId, String keycloakUserId, String email) {}

    private SeededUser seedActiveUser() throws Exception {
        String slug = "acme-" + unique();
        String email = "owner-" + unique() + "@pallet-test.local";
        var result = mvc.perform(post("/api/v1/identity/signup")
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
        String keycloakUserId =
                identityUserRepository.findByEmail(email).orElseThrow().getKeycloakUserId();
        return new SeededUser(orgId, keycloakUserId, email);
    }

    private boolean isEnabledInKeycloak(String keycloakUserId) {
        return keycloakAdminClient
                .realm(KeycloakTestContainerConfiguration.realm())
                .users()
                .get(keycloakUserId)
                .toRepresentation()
                .isEnabled();
    }

    @Test
    void removedMemberIsDisabledInKeycloakAndLocally() throws Exception {
        SeededUser user = seedActiveUser();

        publisher.publish(OrgMemberRemoved.of(user.orgId(), user.keycloakUserId(), user.email()));

        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() ->
                        assertThat(isEnabledInKeycloak(user.keycloakUserId())).isFalse());
        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(identityUserRepository
                                .findByKeycloakUserId(user.keycloakUserId())
                                .orElseThrow()
                                .getStatus())
                        .isEqualTo(UserStatus.DISABLED));
    }

    @Test
    void redeliveryOfTheSameEventIsANoOpWithNoSecondKeycloakCall() throws Exception {
        SeededUser user = seedActiveUser();
        OrgMemberRemoved event = OrgMemberRemoved.of(user.orgId(), user.keycloakUserId(), user.email());

        publisher.publish(event);
        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() ->
                        assertThat(isEnabledInKeycloak(user.keycloakUserId())).isFalse());
        int callsAfterFirstDelivery = keycloakAdminCallCount.get();

        publisher.publish(event);
        await().pollDelay(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(keycloakAdminCallCount.get()).isEqualTo(callsAfterFirstDelivery));
        assertThat(isEnabledInKeycloak(user.keycloakUserId())).isFalse();
    }

    @Test
    void unknownOrgAndUserIsLoggedAsANoOpAndDoesNotBlockLaterMessages() throws Exception {
        publisher.publish(OrgMemberRemoved.of("org-" + unique(), "nonexistent-" + unique(), "ghost@pallet-test.local"));

        SeededUser user = seedActiveUser();
        publisher.publish(OrgMemberRemoved.of(user.orgId(), user.keycloakUserId(), user.email()));

        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() ->
                        assertThat(isEnabledInKeycloak(user.keycloakUserId())).isFalse());
    }
}
