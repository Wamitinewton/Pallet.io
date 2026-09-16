package io.pallet.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.pallet.common.test.annotations.UnitTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.security.oauth2.jwt.Jwt;

@UnitTest
class SessionRevocationValidatorTest {

    @Mock
    private RevokedSessionRegistry registry;

    @InjectMocks
    private SessionRevocationValidator validator;

    private static Jwt jwtWithSessionId(String sessionId) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sid", sessionId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    @Test
    void aTokenWhoseSessionWasNeverRevokedPasses() {
        when(registry.isRevoked("session-1")).thenReturn(false);

        assertThat(validator.validate(jwtWithSessionId("session-1")).hasErrors())
                .isFalse();
    }

    @Test
    void aTokenWhoseSessionWasRevokedFails() {
        when(registry.isRevoked("session-1")).thenReturn(true);

        assertThat(validator.validate(jwtWithSessionId("session-1")).hasErrors())
                .isTrue();
    }

    @Test
    void aTokenWithNoSessionClaimIsNotCheckedAgainstTheRegistry() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "service-account")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        assertThat(validator.validate(jwt).hasErrors()).isFalse();
        assertThat(jwt.getClaims()).doesNotContainKey("sid");
    }
}
