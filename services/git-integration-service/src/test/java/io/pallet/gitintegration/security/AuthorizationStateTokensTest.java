package io.pallet.gitintegration.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.common.test.annotations.UnitTest;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.security.AccessExceptions.InvalidAuthorizationStateException;
import io.pallet.gitintegration.security.AuthorizationStateTokens.IssuedState;
import io.pallet.gitintegration.support.MutableClock;
import io.pallet.gitintegration.support.TestSecrets;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@UnitTest
class AuthorizationStateTokensTest {

    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String USER = "user-1";
    private static final String ORG = "org-1";

    private final JsonMapper json = JsonMapper.builder().build();
    private final MutableClock clock = new MutableClock(NOW);

    @Mock
    private AuthorizationStateRepository states;

    private AuthorizationStateTokens tokens;

    @BeforeEach
    void setUp() {
        tokens = new AuthorizationStateTokens(
                states, properties(TestSecrets.STATE_SIGNING_KEY), clock, json, new SimpleMeterRegistry());
        lenient().when(states.insert(any(), any(), anyString(), any(), eq(TTL))).thenReturn(NOW.plus(TTL));
    }

    @Test
    void anIssuedStateConsumesForItsPurposeUserAndOrg() {
        IssuedState issued = tokens.issue(AuthorizationPurpose.INSTALL, USER, ORG);
        when(states.consume(any(), eq(AuthorizationPurpose.INSTALL), eq(USER), eq(ORG)))
                .thenReturn(true);

        assertThat(tokens.consume(issued.state(), AuthorizationPurpose.INSTALL, USER, ORG)
                        .orgId())
                .isEqualTo(ORG);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(issued.toString()).doesNotContain(issued.state());
    }

    @Test
    void everyPayloadFailureIsTheSameErrorAndNeverReachesTheDatabase() {
        String install = tokens.issue(AuthorizationPurpose.INSTALL, USER, ORG).state();
        String authorize =
                tokens.issue(AuthorizationPurpose.AUTHORIZE, USER, null).state();
        String payload = install.substring(0, install.indexOf('.'));
        String signature = install.substring(install.indexOf('.') + 1);

        List<Supplier<Object>> failures = List.of(
                () -> tokens.consume(tamperedPayload(install), AuthorizationPurpose.INSTALL, USER, ORG),
                () -> tokens.consume(payload + "." + flipFirst(signature), AuthorizationPurpose.INSTALL, USER, ORG),
                () -> tokens.consume(install, AuthorizationPurpose.AUTHORIZE, USER, null),
                () -> tokens.consume(install, AuthorizationPurpose.INSTALL, "user-2", ORG),
                () -> tokens.consume(install, AuthorizationPurpose.INSTALL, USER, "org-2"),
                () -> tokens.consume(authorize, AuthorizationPurpose.INSTALL, USER, ORG),
                () -> tokens.consume("not-a-state", AuthorizationPurpose.INSTALL, USER, ORG),
                () -> tokens.consume(payload + "." + signature + ".x", AuthorizationPurpose.INSTALL, USER, ORG),
                () -> tokens.consume("x".repeat(600), AuthorizationPurpose.INSTALL, USER, ORG),
                () -> tokens.consume(null, AuthorizationPurpose.INSTALL, USER, ORG));

        for (Supplier<Object> failure : failures) {
            assertThatThrownBy(failure::get)
                    .isInstanceOf(InvalidAuthorizationStateException.class)
                    .hasMessage("Authorization state rejected");
        }
        verify(states, never()).consume(any(), any(), any(), any());
    }

    @Test
    void anExpiredStateFailsBeforeTheDatabase() {
        String state = tokens.issue(AuthorizationPurpose.AUTHORIZE, USER, null).state();
        clock.advance(TTL);

        assertThatThrownBy(() -> tokens.consume(state, AuthorizationPurpose.AUTHORIZE, USER, null))
                .isInstanceOf(InvalidAuthorizationStateException.class);
        verify(states, never()).consume(any(), any(), any(), isNull());
    }

    @Test
    void aStateSignedWithAnotherKeyIsRejected() {
        AuthorizationStateTokens other = new AuthorizationStateTokens(
                states, properties(TestSecrets.base64(32)), clock, json, new SimpleMeterRegistry());
        String forged = other.issue(AuthorizationPurpose.AUTHORIZE, USER, null).state();

        assertThatThrownBy(() -> tokens.consume(forged, AuthorizationPurpose.AUTHORIZE, USER, null))
                .isInstanceOf(InvalidAuthorizationStateException.class);
    }

    @Test
    void aStateTheDatabaseRefusesIsTheSameError() {
        String state = tokens.issue(AuthorizationPurpose.AUTHORIZE, USER, null).state();
        when(states.consume(any(), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> tokens.consume(state, AuthorizationPurpose.AUTHORIZE, USER, null))
                .isInstanceOf(InvalidAuthorizationStateException.class)
                .hasMessage("Authorization state rejected");
    }

    @Test
    void anOrgIsBoundToInstallAndNothingElse() {
        assertThatThrownBy(() -> tokens.issue(AuthorizationPurpose.INSTALL, USER, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.issue(AuthorizationPurpose.AUTHORIZE, USER, ORG))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String tamperedPayload(String state) {
        int dot = state.indexOf('.');
        ObjectNode payload = (ObjectNode) json.readTree(Base64.getUrlDecoder().decode(state.substring(0, dot)));
        payload.put("o", "org-2");
        return Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(json.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8))
                + state.substring(dot);
    }

    private static String flipFirst(String value) {
        return (value.charAt(0) == 'A' ? 'B' : 'A') + value.substring(1);
    }

    private static GitIntegrationProperties properties(String signingKey) {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put("pallet.git.github.app-id", "1");
        source.put("pallet.git.github.client-id", "client");
        source.put("pallet.git.github.app-slug", "pallet");
        source.put("pallet.git.authorization.state-signing-key", signingKey);
        source.put("pallet.git.authorization.state-ttl", TTL.toString());
        return new Binder(source)
                .bind("pallet.git", GitIntegrationProperties.class)
                .get();
    }
}
