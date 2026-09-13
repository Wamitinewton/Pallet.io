package io.pallet.identity.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.events.OrgMemberRemoved;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.MessagingProperties;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import io.pallet.common.test.json.JsonTestSupport;
import io.pallet.identity.account.IdentityUserRepository;
import io.pallet.identity.auth.VerifyEmailRequest;
import io.pallet.identity.config.InviteProperties;
import io.pallet.identity.invite.InviteAcceptRequest;
import io.pallet.identity.keycloak.KeycloakAdminTestConfiguration;
import io.pallet.identity.signup.OrgBootstrapRecord;
import io.pallet.identity.signup.OrgBootstrapRepository;
import io.pallet.identity.signup.SignupRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/**
 * Confirms checkpoint 12's counters are wired at the points earlier checkpoints already
 * established, and that the checkpoint's rate-limiting decision actually rejects a burst — not
 * that sign-up/invite/auth/verification/listener *behavior* changed.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import({
    KeycloakTestContainerConfiguration.class,
    KeycloakAdminTestConfiguration.class,
    IdentityMetricsIntegrationTest.EventCaptureConfiguration.class
})
class IdentityMetricsIntegrationTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final String PASSWORD = "supersecretpassword";
    private static final String ROLE = "admin";

    private static final String SIGNUPS_METRIC = "identity.signups";
    private static final String COMPENSATING_DELETE_METRIC = "identity.signups.compensating_delete";
    private static final String INVITES_REPLAYED_METRIC = "identity.invites.replayed";
    private static final String LOGINS_FAILED_METRIC = "identity.logins.failed";
    private static final String LOGINS_UNVERIFIED_METRIC = "identity.logins.unverified";
    private static final String EMAIL_VERIFICATION_REQUESTED_METRIC = "identity.email_verification.requested";
    private static final String EMAIL_VERIFICATION_VERIFIED_METRIC = "identity.email_verification.verified";
    private static final String EMAIL_VERIFICATION_ATTEMPTS_EXHAUSTED_METRIC =
            "identity.email_verification.attempts_exhausted";
    private static final String MEMBERSHIP_SYNC_NOOP_METRIC = "identity.membership_sync.noop";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private IdentityUserRepository identityUserRepository;

    @Autowired
    private InviteProperties inviteProperties;

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private MessagingProperties messagingProperties;

    @Autowired
    private EventCaptureConfiguration.CapturedEvents capturedEvents;

    @MockitoSpyBean
    private OrgBootstrapRepository orgBootstrapRepository;

    @BeforeEach
    void ensureMembershipListenerHasSettled() {
        ContainerTestUtils.waitForAssignment(
                listenerRegistry.getListenerContainer("org-member-removed-listener"),
                messagingProperties.topicPartitions());
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private double counter(String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private String signUp() throws Exception {
        String slug = "acme-" + unique();
        String email = "owner-" + unique() + "@pallet-test.local";
        mvc.perform(post("/api/v1/signup")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(
                                new SignupRequest("Acme " + slug, slug, email, "Ada Lovelace", PASSWORD))))
                .andExpect(status().isCreated());
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

    private void verifyEmail(String email) throws Exception {
        String rawCode = awaitAndExtractCode(email);
        mvc.perform(post("/api/v1/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new VerifyEmailRequest(email, rawCode))))
                .andExpect(status().isOk());
    }

    private record LoginJson(String email, String password) {}

    private MvcResult performLogin(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new LoginJson(email, password))))
                .andReturn();
    }

    private String inviteToken(String orgId, String email, String jti, Duration ttl) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .claim("purpose", "invite")
                    .claim("orgId", orgId)
                    .claim("email", email)
                    .claim("role", ROLE)
                    .jwtID(jti)
                    .expirationTime(Date.from(Instant.now().plus(ttl)))
                    .build();
            SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            signedJwt.sign(new MACSigner(inviteProperties.signingKey().getBytes(StandardCharsets.UTF_8)));
            return signedJwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MvcResult performInviteAccept(String token) throws Exception {
        return mvc.perform(post("/api/v1/invites/{token}/accept", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new InviteAcceptRequest(PASSWORD))))
                .andReturn();
    }

    @Test
    void signUpIncrementsSignupsAndEmailVerificationRequestedForSignupSource() throws Exception {
        double beforeSignups = counter(SIGNUPS_METRIC);
        double beforeRequested = counter(EMAIL_VERIFICATION_REQUESTED_METRIC, "source", "signup");

        signUp();

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            assertThat(counter(SIGNUPS_METRIC)).isEqualTo(beforeSignups + 1);
            assertThat(counter(EMAIL_VERIFICATION_REQUESTED_METRIC, "source", "signup"))
                    .isEqualTo(beforeRequested + 1);
        });
    }

    @Test
    void aLocalCommitFailureAfterKeycloakSuccessIncrementsCompensatingDelete() throws Exception {
        String slug = "acme-" + unique();
        String email = "owner-" + unique() + "@pallet-test.local";
        double before = counter(COMPENSATING_DELETE_METRIC);
        doThrow(new DataIntegrityViolationException("simulated race the pre-check missed"))
                .when(orgBootstrapRepository)
                .save(argThat((OrgBootstrapRecord record) -> slug.equals(record.getSlug())));

        mvc.perform(post("/api/v1/signup")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(
                                new SignupRequest("Acme " + slug, slug, email, "Ada Lovelace", PASSWORD))))
                .andExpect(status().isConflict());

        assertThat(counter(COMPENSATING_DELETE_METRIC)).isEqualTo(before + 1);
    }

    @Test
    void replayingAnAcceptedInviteIncrementsReplayedCounter() throws Exception {
        String orgId = "org-" + unique();
        String email = "invitee-" + unique() + "@pallet-test.local";
        String token = inviteToken(orgId, email, UUID.randomUUID().toString(), Duration.ofMinutes(15));
        double before = counter(INVITES_REPLAYED_METRIC);

        performInviteAccept(token);
        assertThat(performInviteAccept(token).getResponse().getStatus()).isEqualTo(409);

        assertThat(counter(INVITES_REPLAYED_METRIC)).isEqualTo(before + 1);
    }

    @Test
    void wrongPasswordIncrementsLoginsFailedWithoutPublishingALoginAuditEvent() throws Exception {
        String email = signUp();
        verifyEmail(email);
        String keycloakUserId =
                identityUserRepository.findByEmail(email).orElseThrow().getKeycloakUserId();
        double before = counter(LOGINS_FAILED_METRIC);

        MvcResult result = performLogin(email, "the-wrong-password");

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(counter(LOGINS_FAILED_METRIC)).isEqualTo(before + 1);
        await().pollDelay(Duration.ofSeconds(2))
                .atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(capturedEvents.auditEvents.stream()
                                .filter(record -> "login"
                                        .equals(record.value().get("action").asString()))
                                .anyMatch(record -> keycloakUserId.equals(
                                        record.value().get("actor").asString())))
                        .isFalse());
    }

    @Test
    void loginAgainstAnUnverifiedAccountIncrementsUnverifiedNotFailed() throws Exception {
        String email = signUp();
        double beforeUnverified = counter(LOGINS_UNVERIFIED_METRIC);
        double beforeFailed = counter(LOGINS_FAILED_METRIC);

        MvcResult result = performLogin(email, PASSWORD);

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(counter(LOGINS_UNVERIFIED_METRIC)).isEqualTo(beforeUnverified + 1);
        assertThat(counter(LOGINS_FAILED_METRIC)).isEqualTo(beforeFailed);
    }

    @Test
    void successfulVerifyIncrementsVerifiedCounter() throws Exception {
        String email = signUp();
        double before = counter(EMAIL_VERIFICATION_VERIFIED_METRIC);

        verifyEmail(email);

        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() ->
                        assertThat(counter(EMAIL_VERIFICATION_VERIFIED_METRIC)).isEqualTo(before + 1));
    }

    @Test
    void exhaustingTheAttemptBudgetIncrementsAttemptsExhaustedCounter() throws Exception {
        String email = signUp();
        awaitAndExtractCode(email);
        double before = counter(EMAIL_VERIFICATION_ATTEMPTS_EXHAUSTED_METRIC);

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/email/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JsonTestSupport.toJson(new VerifyEmailRequest(email, "WRONGCOD"))))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new VerifyEmailRequest(email, "STILLBAD"))))
                .andExpect(status().isBadRequest());

        assertThat(counter(EMAIL_VERIFICATION_ATTEMPTS_EXHAUSTED_METRIC)).isEqualTo(before + 1);
    }

    @Test
    void aMembershipEventForAnUnknownUserIncrementsTheNoopCounter() {
        double before = counter(MEMBERSHIP_SYNC_NOOP_METRIC, "event", "OrgMemberRemoved");

        publisher.publish(OrgMemberRemoved.of("org-" + unique(), "nonexistent-" + unique(), "ghost@pallet-test.local"));

        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(counter(MEMBERSHIP_SYNC_NOOP_METRIC, "event", "OrgMemberRemoved"))
                        .isEqualTo(before + 1));
    }

    @Test
    void aBurstBeyondTheRateLimitIsThrottledWithoutAffectingADifferentCaller() throws Exception {
        String email = "throttle-" + unique() + "@pallet-test.local";
        RequestPostProcessor callerA = request -> {
            request.setRemoteAddr("203.0.113.10");
            return request;
        };
        RequestPostProcessor callerB = request -> {
            request.setRemoteAddr("203.0.113.20");
            return request;
        };

        int lastStatus = 0;
        for (int i = 0; i < 21; i++) {
            lastStatus = mvc.perform(post("/api/v1/auth/email/resend-verification")
                            .with(callerA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JsonTestSupport.toJson(new ResendJson(email))))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        }

        assertThat(lastStatus).isEqualTo(429);
        mvc.perform(post("/api/v1/auth/email/resend-verification")
                        .with(callerB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new ResendJson(email))))
                .andExpect(status().isAccepted());
    }

    private record ResendJson(String email) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class EventCaptureConfiguration {

        @Bean
        CapturedEvents capturedEvents() {
            return new CapturedEvents();
        }

        static class CapturedEvents {

            final List<ConsumerRecord<String, JsonNode>> notificationRequested = new CopyOnWriteArrayList<>();
            final List<ConsumerRecord<String, JsonNode>> auditEvents = new CopyOnWriteArrayList<>();

            @KafkaListener(topics = Topics.NOTIFICATION_REQUESTED, groupId = "identity-metrics-test-notification")
            void onNotificationRequested(ConsumerRecord<String, JsonNode> record) {
                notificationRequested.add(record);
            }

            @KafkaListener(topics = Topics.AUDIT_EVENT_RECORDED, groupId = "identity-metrics-test-audit")
            void onAuditEvent(ConsumerRecord<String, JsonNode> record) {
                auditEvents.add(record);
            }
        }
    }
}
