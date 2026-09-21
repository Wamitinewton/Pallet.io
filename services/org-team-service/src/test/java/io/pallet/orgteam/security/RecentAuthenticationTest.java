package io.pallet.orgteam.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.common.test.annotations.UnitTest;
import io.pallet.orgteam.member.Role;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import io.pallet.orgteam.security.AccessExceptions.ReauthenticationRequiredException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

@UnitTest
class RecentAuthenticationTest {

    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final RecentAuthentication recentAuthentication = new RecentAuthentication(
            Clock.fixed(NOW, ZoneOffset.UTC), WINDOW, new OrgTeamMetrics(new SimpleMeterRegistry()));

    @Test
    void aFreshAuthenticationPasses() {
        assertThatCode(() -> recentAuthentication.require(contextAuthenticatedAt(NOW.minusSeconds(30))))
                .doesNotThrowAnyException();
    }

    @Test
    void anAuthenticationExactlyAtTheWindowEdgePasses() {
        assertThatCode(() -> recentAuthentication.require(contextAuthenticatedAt(NOW.minus(WINDOW))))
                .doesNotThrowAnyException();
    }

    @Test
    void anAuthenticationOlderThanTheWindowIsRejected() {
        assertThatThrownBy(() -> recentAuthentication.require(
                        contextAuthenticatedAt(NOW.minus(WINDOW).minusSeconds(1))))
                .isInstanceOf(ReauthenticationRequiredException.class);
    }

    @Test
    void aMissingAuthTimeFailsClosed() {
        assertThatThrownBy(() -> recentAuthentication.require(contextAuthenticatedAt(null)))
                .isInstanceOf(ReauthenticationRequiredException.class);
    }

    private static AccessContext contextAuthenticatedAt(Instant authTime) {
        return new AccessContext("org-1", "user-1", Role.OWNER, authTime);
    }
}
