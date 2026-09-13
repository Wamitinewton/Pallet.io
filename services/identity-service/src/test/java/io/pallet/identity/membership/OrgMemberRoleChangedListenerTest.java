package io.pallet.identity.membership;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.common.error.ExternalServiceException;
import io.pallet.common.events.OrgMemberRoleChanged;
import io.pallet.common.messaging.EventIdempotencyGuard;
import io.pallet.common.resilience.ExternalCall;
import io.pallet.common.resilience.ExternalCallExecutor;
import io.pallet.common.resilience.ResilienceProperties;
import io.pallet.common.resilience.ResilienceRegistries;
import io.pallet.common.test.annotations.UnitTest;
import io.pallet.identity.config.IdentityServiceProperties;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.mockito.Mock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Covers the two branches an integration test can't exercise cheaply: the guard short-circuit and
 * a genuine Keycloak connection failure. {@link OrgMemberRoleChangedListenerIntegrationTest}
 * covers the success and no-op paths against a real Keycloak.
 */
@UnitTest
class OrgMemberRoleChangedListenerTest {

    private static final String REALM = "test-realm";

    @Mock
    private EventIdempotencyGuard idempotencyGuard;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private Keycloak keycloakAdminClient;

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        if (keycloakAdminClient != null) {
            keycloakAdminClient.close();
        }
    }

    @Test
    void guardShortCircuitSkipsKeycloak() {
        when(idempotencyGuard.markProcessed(anyString(), any())).thenReturn(false);
        ExternalCall externalCall = mock(ExternalCall.class);
        OrgMemberRoleChangedListener listener = new OrgMemberRoleChangedListener(
                idempotencyGuard,
                externalCall,
                mock(Keycloak.class),
                propertiesFor("http://127.0.0.1:1"),
                jsonMapper,
                new SimpleMeterRegistry());

        listener.onMessage(recordFor(OrgMemberRoleChanged.of("org-1", "kc-user-1", "admin", "viewer")));

        verifyNoInteractions(externalCall);
    }

    @Test
    void keycloakFailureReleasesTheGuardAndRethrows() throws IOException {
        when(idempotencyGuard.markProcessed(anyString(), any())).thenReturn(true);
        String serverUrl = "http://127.0.0.1:" + closedPort();
        OrgMemberRoleChangedListener listener = new OrgMemberRoleChangedListener(
                idempotencyGuard,
                fastFailingExternalCall(),
                keycloakClientFor(serverUrl),
                propertiesFor(serverUrl),
                jsonMapper,
                new SimpleMeterRegistry());
        OrgMemberRoleChanged event = OrgMemberRoleChanged.of("org-1", "kc-user-1", "admin", "viewer");

        assertThatThrownBy(() -> listener.onMessage(recordFor(event))).isInstanceOf(ExternalServiceException.class);

        verify(idempotencyGuard).release(event.eventId().toString());
    }

    private ConsumerRecord<String, JsonNode> recordFor(OrgMemberRoleChanged event) {
        return new ConsumerRecord<>("org.member.role.changed", 0, 0, event.orgId(), jsonMapper.valueToTree(event));
    }

    private Keycloak keycloakClientFor(String serverUrl) {
        keycloakAdminClient = KeycloakBuilder.builder()
                .serverUrl(serverUrl)
                .realm(REALM)
                .clientId("admin")
                .clientSecret("secret")
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .build();
        return keycloakAdminClient;
    }

    private IdentityServiceProperties propertiesFor(String serverUrl) {
        return new IdentityServiceProperties(
                new IdentityServiceProperties.KeycloakAdmin(serverUrl, REALM, "admin", "secret", "pallet-test-client"),
                new IdentityServiceProperties.EmailVerification(Duration.ofMinutes(15), 5),
                new IdentityServiceProperties.PasswordReset(Duration.ofHours(1), "http://localhost:5173"),
                new IdentityServiceProperties.RateLimit(20, Duration.ofMinutes(1)));
    }

    private ExternalCall fastFailingExternalCall() {
        ResilienceRegistries registries = new ResilienceRegistries(new ResilienceProperties(
                new ResilienceProperties.Policy(
                        new ResilienceProperties.CircuitBreaker(
                                50, 10, 10, Duration.ofSeconds(30), 3, 50, Duration.ofSeconds(5)),
                        new ResilienceProperties.Retry(2, Duration.ofMillis(10), 1.0, Duration.ofMillis(50)),
                        new ResilienceProperties.TimeLimiter(Duration.ofMillis(500), true)),
                Map.of()));
        return new ExternalCallExecutor(registries, executor);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
