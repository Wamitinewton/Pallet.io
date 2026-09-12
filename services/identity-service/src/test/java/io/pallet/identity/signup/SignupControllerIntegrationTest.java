package io.pallet.identity.signup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.events.Topics;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import io.pallet.common.test.json.JsonTestSupport;
import io.pallet.identity.keycloak.KeycloakAdminTestConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

@IntegrationTest
@AutoConfigureMockMvc
@Import({
    KeycloakTestContainerConfiguration.class,
    KeycloakAdminTestConfiguration.class,
    SignupControllerIntegrationTest.EventCaptureConfiguration.class
})
class SignupControllerIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Keycloak keycloakAdminClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EventCaptureConfiguration.CapturedEvents capturedEvents;

    @MockitoSpyBean
    private OrgBootstrapRepository orgBootstrapRepository;

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String signupJson(String slug, String email) {
        return JsonTestSupport.toJson(
                new SignupRequest("Acme " + slug, slug, email, "Ada Lovelace", "supersecretpassword"));
    }

    private ResultActions performSignup(String idempotencyKey, String body) throws Exception {
        return mvc.perform(post("/api/v1/signup")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String orgIdFrom(MvcResult result) throws Exception {
        JsonNode json = JsonTestSupport.MAPPER.readTree(result.getResponse().getContentAsString());
        return json.get("data").get("orgId").asString();
    }

    private List<UserRepresentation> keycloakUsersByEmail(String email) {
        return keycloakAdminClient
                .realm(KeycloakTestContainerConfiguration.realm())
                .users()
                .searchByEmail(email, true);
    }

    @Test
    void wellFormedRequestProvisionsTheOwnerAccountAndPublishesEvents() throws Exception {
        String slug = "acme-" + unique();
        String email = "owner-" + unique() + "@pallet-test.local";

        MvcResult result = performSignup(UUID.randomUUID().toString(), signupJson(slug, email))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.slug").value(slug))
                .andReturn();
        String orgId = orgIdFrom(result);

        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from identity.organizations where org_id = ?", Integer.class, orgId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from identity.users where email = ? and org_id = ?",
                        Integer.class,
                        email,
                        orgId))
                .isEqualTo(1);

        List<UserRepresentation> keycloakUsers = keycloakUsersByEmail(email);
        assertThat(keycloakUsers).hasSize(1);
        String keycloakUserId = keycloakUsers.get(0).getId();
        UserRepresentation keycloakUser = keycloakAdminClient
                .realm(KeycloakTestContainerConfiguration.realm())
                .users()
                .get(keycloakUserId)
                .toRepresentation();
        assertThat(keycloakUser.getAttributes()).containsEntry("org_id", List.of(orgId));

        List<RoleRepresentation> roles = keycloakAdminClient
                .realm(KeycloakTestContainerConfiguration.realm())
                .users()
                .get(keycloakUserId)
                .roles()
                .realmLevel()
                .listAll();
        assertThat(roles).extracting(RoleRepresentation::getName).contains("owner");

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(capturedEvents.orgProvisioned)
                        .anyMatch(record ->
                                orgId.equals(record.value().get("orgId").asString())));
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(capturedEvents.notificationRequested)
                        .anyMatch(record ->
                                orgId.equals(record.value().get("orgId").asString())));
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(capturedEvents.auditEvents)
                        .anyMatch(record ->
                                orgId.equals(record.value().get("orgId").asString())));
    }

    @Test
    void replayingTheSameIdempotencyKeyWithAnIdenticalBodyReturnsTheCachedResponse() throws Exception {
        String slug = "acme-" + unique();
        String email = "owner-" + unique() + "@pallet-test.local";
        String body = signupJson(slug, email);
        String key = UUID.randomUUID().toString();

        MvcResult first =
                performSignup(key, body).andExpect(status().isCreated()).andReturn();
        MvcResult second =
                performSignup(key, body).andExpect(status().isCreated()).andReturn();

        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(keycloakUsersByEmail(email)).hasSize(1);
    }

    @Test
    void replayingTheSameIdempotencyKeyWithADifferentBodyIsConflict() throws Exception {
        String key = UUID.randomUUID().toString();
        performSignup(key, signupJson("acme-" + unique(), "owner-" + unique() + "@pallet-test.local"))
                .andExpect(status().isCreated());

        performSignup(key, signupJson("acme-" + unique(), "owner-" + unique() + "@pallet-test.local"))
                .andExpect(status().isConflict());
    }

    @Test
    void aTakenSlugIsRejectedWithoutCreatingAKeycloakUser() throws Exception {
        String slug = "acme-" + unique();
        performSignup(UUID.randomUUID().toString(), signupJson(slug, "owner-" + unique() + "@pallet-test.local"))
                .andExpect(status().isCreated());

        String rejectedEmail = "owner-" + unique() + "@pallet-test.local";
        performSignup(UUID.randomUUID().toString(), signupJson(slug, rejectedEmail))
                .andExpect(status().isConflict());

        assertThat(keycloakUsersByEmail(rejectedEmail)).isEmpty();
    }

    @Test
    void aLocalTransactionFailureAfterKeycloakSuccessTriggersACompensatingDelete() throws Exception {
        String slug = "acme-" + unique();
        String email = "owner-" + unique() + "@pallet-test.local";
        doThrow(new DataIntegrityViolationException("simulated race the pre-check missed"))
                .when(orgBootstrapRepository)
                .save(argThat((OrgBootstrapRecord record) -> slug.equals(record.getSlug())));

        performSignup(UUID.randomUUID().toString(), signupJson(slug, email)).andExpect(status().isConflict());

        assertThat(keycloakUsersByEmail(email)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from identity.organizations where slug = ?", Integer.class, slug))
                .isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from identity.users where email = ?", Integer.class, email))
                .isEqualTo(0);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EventCaptureConfiguration {

        @Bean
        CapturedEvents capturedEvents() {
            return new CapturedEvents();
        }

        static class CapturedEvents {

            final List<ConsumerRecord<String, JsonNode>> orgProvisioned = new CopyOnWriteArrayList<>();
            final List<ConsumerRecord<String, JsonNode>> notificationRequested = new CopyOnWriteArrayList<>();
            final List<ConsumerRecord<String, JsonNode>> auditEvents = new CopyOnWriteArrayList<>();

            @KafkaListener(topics = Topics.ORG_PROVISIONED, groupId = "signup-test-org-provisioned")
            void onOrgProvisioned(ConsumerRecord<String, JsonNode> record) {
                orgProvisioned.add(record);
            }

            @KafkaListener(topics = Topics.NOTIFICATION_REQUESTED, groupId = "signup-test-notification-requested")
            void onNotificationRequested(ConsumerRecord<String, JsonNode> record) {
                notificationRequested.add(record);
            }

            @KafkaListener(topics = Topics.AUDIT_EVENT_RECORDED, groupId = "signup-test-audit-event")
            void onAuditEvent(ConsumerRecord<String, JsonNode> record) {
                auditEvents.add(record);
            }
        }
    }
}
