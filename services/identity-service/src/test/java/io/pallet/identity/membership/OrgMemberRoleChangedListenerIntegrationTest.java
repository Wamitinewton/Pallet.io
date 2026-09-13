package io.pallet.identity.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.events.OrgMemberRoleChanged;
import io.pallet.common.messaging.MessagingProperties;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import io.pallet.common.test.json.JsonTestSupport;
import io.pallet.identity.account.IdentityUserRepository;
import io.pallet.identity.keycloak.KeycloakAdminTestConfiguration;
import io.pallet.identity.signup.SignupRequest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RoleRepresentation;
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
class OrgMemberRoleChangedListenerIntegrationTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final String PASSWORD = "supersecretpassword";
    private static final String OWNER_ROLE = "owner";

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
                listenerRegistry.getListenerContainer("org-member-role-changed-listener"),
                messagingProperties.topicPartitions());
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record SeededUser(String orgId, String keycloakUserId, String email) {}

    private SeededUser seedActiveUser() throws Exception {
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
        String keycloakUserId =
                identityUserRepository.findByEmail(email).orElseThrow().getKeycloakUserId();
        return new SeededUser(orgId, keycloakUserId, email);
    }

    /**
     * Replaces signup's default {@code owner} assignment with {@code initialRole}, so the test has
     * a known starting point to prove the listener's remove-then-verify behavior against.
     */
    private void assignInitialRole(String keycloakUserId, String initialRole) {
        var roleScope = keycloakAdminClient
                .realm(KeycloakTestContainerConfiguration.realm())
                .users()
                .get(keycloakUserId)
                .roles()
                .realmLevel();
        roleScope.remove(List.of(roleRepresentation(OWNER_ROLE)));
        roleScope.add(List.of(roleRepresentation(initialRole)));
    }

    private RoleRepresentation roleRepresentation(String role) {
        return keycloakAdminClient
                .realm(KeycloakTestContainerConfiguration.realm())
                .roles()
                .get(role)
                .toRepresentation();
    }

    private List<String> effectiveRoleNames(String keycloakUserId) {
        return keycloakAdminClient
                .realm(KeycloakTestContainerConfiguration.realm())
                .users()
                .get(keycloakUserId)
                .roles()
                .realmLevel()
                .listEffective()
                .stream()
                .map(RoleRepresentation::getName)
                .toList();
    }

    @Test
    void roleChangeReplacesTheEffectiveRealmRoleAssignment() throws Exception {
        SeededUser user = seedActiveUser();
        assignInitialRole(user.keycloakUserId(), "admin");

        publisher.publish(OrgMemberRoleChanged.of(user.orgId(), user.keycloakUserId(), "admin", "viewer"));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            List<String> roles = effectiveRoleNames(user.keycloakUserId());
            assertThat(roles).contains("viewer").doesNotContain("admin");
        });
    }

    @Test
    void redeliveryOfTheSameEventIsANoOpWithNoSecondKeycloakCall() throws Exception {
        SeededUser user = seedActiveUser();
        assignInitialRole(user.keycloakUserId(), "admin");
        OrgMemberRoleChanged event = OrgMemberRoleChanged.of(user.orgId(), user.keycloakUserId(), "admin", "viewer");

        publisher.publish(event);
        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(effectiveRoleNames(user.keycloakUserId()))
                        .contains("viewer")
                        .doesNotContain("admin"));
        int callsAfterFirstDelivery = keycloakAdminCallCount.get();

        publisher.publish(event);
        await().pollDelay(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(keycloakAdminCallCount.get()).isEqualTo(callsAfterFirstDelivery));
    }

    @Test
    void unknownUserIsLoggedAsANoOpAndDoesNotBlockLaterMessages() throws Exception {
        publisher.publish(OrgMemberRoleChanged.of("org-" + unique(), "nonexistent-" + unique(), "admin", "viewer"));

        SeededUser user = seedActiveUser();
        assignInitialRole(user.keycloakUserId(), "admin");
        publisher.publish(OrgMemberRoleChanged.of(user.orgId(), user.keycloakUserId(), "admin", "viewer"));

        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(effectiveRoleNames(user.keycloakUserId()))
                        .contains("viewer")
                        .doesNotContain("admin"));
    }
}
