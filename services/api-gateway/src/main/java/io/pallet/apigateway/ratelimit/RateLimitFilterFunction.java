package io.pallet.apigateway.ratelimit;

import io.pallet.apigateway.config.GatewayProperties;
import io.pallet.apigateway.config.GatewayProperties.RateLimitMode;
import io.pallet.apigateway.config.GatewayProperties.RateLimitOverride;
import io.pallet.common.error.ErrorResponse;
import io.pallet.common.security.OrgContext;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Coarse, Redis-backed rate limit applied first in every route's filter chain, ahead of the
 * circuit breaker/retry pair — a rejected request never consumes a retry attempt or counts toward
 * a breaker's failure window. Additive to, never a replacement for, {@code identity-service}'s own
 * {@code AuthRateLimiter}.
 */
@Component
public class RateLimitFilterFunction implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    private static final String RATE_LIMITED_MESSAGE = "Too many requests";
    private static final String RATE_LIMITED_CODE = "TOO_MANY_REQUESTS";
    private static final String OVERRIDE_KEY_PREFIX = "override:";

    private final RateLimitProperties properties;
    private final RedisTokenBucketRateLimiter limiter;

    RateLimitFilterFunction(RateLimitProperties properties, RedisTokenBucketRateLimiter limiter) {
        this.properties = properties;
        this.limiter = limiter;
    }

    @Override
    public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
        if (!properties.enabled()) {
            return next.handle(request);
        }
        return admit(request, limiter.tryConsume(subjectKeyFor(request)), next);
    }

    public HandlerFilterFunction<ServerResponse, ServerResponse> forRoute(GatewayProperties.Route route) {
        if (route.rateLimits().isEmpty()) {
            return this;
        }
        List<PatternOverride> overrides = route.rateLimits().stream()
                .map(override ->
                        new PatternOverride(PathPatternParser.defaultInstance.parse(override.path()), override))
                .sorted(Comparator.comparing(PatternOverride::pattern, PathPattern.SPECIFICITY_COMPARATOR))
                .toList();
        return (request, next) -> {
            if (!properties.enabled()) {
                return next.handle(request);
            }
            Optional<RateLimitOverride> match = mostSpecificMatch(overrides, request.path());
            if (match.isEmpty() || match.get().mode() == RateLimitMode.DEFAULT) {
                return filter(request, next);
            }
            RateLimitOverride override = match.get();
            if (override.mode() == RateLimitMode.DISABLED) {
                return next.handle(request);
            }
            String key = OVERRIDE_KEY_PREFIX + override.path() + ":" + subjectKeyFor(request);
            return admit(request, limiter.tryConsume(key, override.capacity(), override.window()), next);
        };
    }

    private static Optional<RateLimitOverride> mostSpecificMatch(List<PatternOverride> overrides, String path) {
        PathContainer container = PathContainer.parsePath(path);
        return overrides.stream()
                .filter(candidate -> candidate.pattern().matches(container))
                .map(PatternOverride::override)
                .findFirst();
    }

    private static ServerResponse admit(
            ServerRequest request, RedisTokenBucketRateLimiter.Decision decision, HandlerFunction<ServerResponse> next)
            throws Exception {
        if (!decision.allowed()) {
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, retryAfterSeconds(decision.retryAfter()))
                    .body(ErrorResponse.of(
                            HttpStatus.TOO_MANY_REQUESTS, RATE_LIMITED_MESSAGE, RATE_LIMITED_CODE, request.path()));
        }
        return next.handle(request);
    }

    // Retry-After only carries whole seconds; rounding down would invite a retry that's still early.
    private static String retryAfterSeconds(Duration retryAfter) {
        return String.valueOf(Math.max(1, Math.ceilDiv(retryAfter.toMillis(), 1000L)));
    }

    private static String subjectKeyFor(ServerRequest request) {
        return OrgContext.currentOrgId()
                .orElseGet(() -> request.remoteAddress()
                        .map(address -> address.getAddress().getHostAddress())
                        .orElse("unknown"));
    }

    private record PatternOverride(PathPattern pattern, RateLimitOverride override) {}
}
