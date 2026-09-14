package io.pallet.apigateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.pallet.common.test.annotations.UnitTest;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

@UnitTest
class RateLimitFilterFunctionTest {

    @Mock
    private RedisTokenBucketRateLimiter limiter;

    @Mock
    private ServerRequest request;

    @Mock
    private HandlerFunction<ServerResponse> next;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesTheSubjectKeyFromTheOrgIdClaimWhenAuthenticated() throws Exception {
        authenticateWithOrgId("org-42");
        when(limiter.tryConsume("org-42")).thenReturn(true);
        when(next.handle(request)).thenReturn(ServerResponse.ok().build());

        new RateLimitFilterFunction(enabledProperties(), limiter).filter(request, next);

        verify(limiter).tryConsume("org-42");
    }

    @Test
    void resolvesTheSubjectKeyFromTheRemoteAddressWhenUnauthenticated() throws Exception {
        when(request.remoteAddress()).thenReturn(Optional.of(new InetSocketAddress("203.0.113.7", 51000)));
        when(limiter.tryConsume("203.0.113.7")).thenReturn(true);
        when(next.handle(request)).thenReturn(ServerResponse.ok().build());

        new RateLimitFilterFunction(enabledProperties(), limiter).filter(request, next);

        verify(limiter).tryConsume("203.0.113.7");
    }

    @Test
    void shortCircuitsToTooManyRequestsWithoutCallingNextWhenOverBudget() throws Exception {
        when(request.remoteAddress()).thenReturn(Optional.of(new InetSocketAddress("203.0.113.7", 51000)));
        when(request.path()).thenReturn("/api/v1/identity/auth/login");
        when(limiter.tryConsume("203.0.113.7")).thenReturn(false);

        ServerResponse response = new RateLimitFilterFunction(enabledProperties(), limiter).filter(request, next);

        assertThat(response.statusCode().value()).isEqualTo(429);
        verify(next, never()).handle(request);
    }

    @Test
    void bypassesTheLimiterEntirelyWhenDisabled() throws Exception {
        when(next.handle(request)).thenReturn(ServerResponse.ok().build());

        new RateLimitFilterFunction(disabledProperties(), limiter).filter(request, next);

        verifyNoInteractions(limiter);
    }

    private static void authenticateWithOrgId(String orgId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("org_id", orgId)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(jwt, null));
    }

    private static RateLimitProperties enabledProperties() {
        return new RateLimitProperties(true, 60, Duration.ofMinutes(1));
    }

    private static RateLimitProperties disabledProperties() {
        return new RateLimitProperties(false, 60, Duration.ofMinutes(1));
    }
}
