package io.pallet.apigateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.pallet.apigateway.config.GatewayProperties.RateLimitMode;
import io.pallet.apigateway.config.GatewayProperties.RateLimitOverride;
import io.pallet.apigateway.config.GatewayProperties.Route;
import io.pallet.common.test.annotations.UnitTest;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

@UnitTest
class RouteRateLimitOverrideTest {

    private static final String BASE = "/api/v1/git-integration";
    private static final String WEBHOOK = BASE + "/webhooks/github";
    private static final String CLIENT_IP = "203.0.113.7";

    @Mock
    private RedisTokenBucketRateLimiter limiter;

    @Mock
    private ServerRequest request;

    @Mock
    private HandlerFunction<ServerResponse> next;

    @BeforeEach
    void stubRemoteAddress() {
        lenient().when(request.remoteAddress()).thenReturn(Optional.of(new InetSocketAddress(CLIENT_IP, 51000)));
    }

    @Test
    void aDisabledOverrideSkipsTheLimiterEntirely() throws Exception {
        when(request.path()).thenReturn(WEBHOOK);
        when(next.handle(request)).thenReturn(ServerResponse.ok().build());

        filterFor(route(List.of(WEBHOOK), new RateLimitOverride(WEBHOOK, RateLimitMode.DISABLED, null, null)))
                .filter(request, next);

        verifyNoInteractions(limiter);
        verify(next).handle(request);
    }

    @Test
    void aPathWithNoMatchingOverrideKeepsTheGlobalLimit() throws Exception {
        when(request.path()).thenReturn(BASE + "/v3/api-docs");
        when(limiter.tryConsume(CLIENT_IP)).thenReturn(RedisTokenBucketRateLimiter.Decision.ALLOWED);
        when(next.handle(request)).thenReturn(ServerResponse.ok().build());

        filterFor(route(List.of(WEBHOOK), new RateLimitOverride(WEBHOOK, RateLimitMode.DISABLED, null, null)))
                .filter(request, next);

        verify(limiter).tryConsume(CLIENT_IP);
    }

    @Test
    void aDefaultOverrideKeepsTheGlobalLimit() throws Exception {
        when(request.path()).thenReturn(WEBHOOK);
        when(limiter.tryConsume(CLIENT_IP)).thenReturn(RedisTokenBucketRateLimiter.Decision.ALLOWED);
        when(next.handle(request)).thenReturn(ServerResponse.ok().build());

        filterFor(route(List.of(), new RateLimitOverride(WEBHOOK, RateLimitMode.DEFAULT, null, null)))
                .filter(request, next);

        verify(limiter).tryConsume(CLIENT_IP);
    }

    @Test
    void aCustomOverrideUsesItsOwnCapacityWindowAndKey() throws Exception {
        when(request.path()).thenReturn(WEBHOOK);
        when(limiter.tryConsume("override:" + WEBHOOK + ":" + CLIENT_IP, 500, Duration.ofSeconds(10)))
                .thenReturn(RedisTokenBucketRateLimiter.Decision.rejected(Duration.ofSeconds(1)));

        ServerResponse response = filterFor(route(
                        List.of(), new RateLimitOverride(WEBHOOK, RateLimitMode.CUSTOM, 500, Duration.ofSeconds(10))))
                .filter(request, next);

        assertThat(response.statusCode().value()).isEqualTo(429);
        verify(next, never()).handle(request);
    }

    @Test
    void theMostSpecificPatternWins() throws Exception {
        when(request.path()).thenReturn(WEBHOOK);
        when(next.handle(request)).thenReturn(ServerResponse.ok().build());

        filterFor(route(
                        List.of(WEBHOOK),
                        new RateLimitOverride(BASE + "/**", RateLimitMode.CUSTOM, 1, Duration.ofMinutes(1)),
                        new RateLimitOverride(WEBHOOK, RateLimitMode.DISABLED, null, null),
                        new RateLimitOverride(BASE + "/webhooks/*", RateLimitMode.CUSTOM, 2, Duration.ofMinutes(1))))
                .filter(request, next);

        verifyNoInteractions(limiter);
    }

    @Test
    void theGlobalSwitchStillTurnsEveryOverrideOff() throws Exception {
        when(next.handle(request)).thenReturn(ServerResponse.ok().build());

        new RateLimitFilterFunction(new RateLimitProperties(false, 60, Duration.ofMinutes(1)), limiter)
                .forRoute(route(
                        List.of(), new RateLimitOverride(WEBHOOK, RateLimitMode.CUSTOM, 1, Duration.ofMinutes(1))))
                .filter(request, next);

        verifyNoInteractions(limiter);
    }

    @Test
    void startupRejectsDisablingTheLimitOnANonPublicPath() {
        assertThatThrownBy(() -> route(List.of(), new RateLimitOverride(WEBHOOK, RateLimitMode.DISABLED, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public-paths");
    }

    @Test
    void startupRejectsACustomOverrideWithoutACapacity() {
        assertThatThrownBy(() -> new RateLimitOverride(WEBHOOK, RateLimitMode.CUSTOM, null, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
    }

    @Test
    void startupRejectsACustomOverrideWithoutAWindow() {
        assertThatThrownBy(() -> new RateLimitOverride(WEBHOOK, RateLimitMode.CUSTOM, 10, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startupRejectsAnOverrideOutsideItsRoute() {
        String elsewhere = "/api/v1/identity/auth/login";
        assertThatThrownBy(() ->
                        route(List.of(elsewhere), new RateLimitOverride(elsewhere, RateLimitMode.DISABLED, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");
    }

    private HandlerFilterFunction<ServerResponse, ServerResponse> filterFor(Route route) {
        return new RateLimitFilterFunction(new RateLimitProperties(true, 60, Duration.ofMinutes(1)), limiter)
                .forRoute(route);
    }

    private static Route route(List<String> publicPaths, RateLimitOverride... overrides) {
        return new Route(
                URI.create("http://localhost:8085"),
                BASE + "/**",
                List.of(HttpMethod.GET, HttpMethod.POST),
                publicPaths,
                "git-integration-service",
                List.of(overrides));
    }
}
