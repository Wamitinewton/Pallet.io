package io.pallet.apigateway.ratelimit;

import io.pallet.common.error.ErrorResponse;
import io.pallet.common.security.OrgContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

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
        if (!limiter.tryConsume(subjectKeyFor(request))) {
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ErrorResponse.of(
                            HttpStatus.TOO_MANY_REQUESTS, RATE_LIMITED_MESSAGE, RATE_LIMITED_CODE, request.path()));
        }
        return next.handle(request);
    }

    private static String subjectKeyFor(ServerRequest request) {
        return OrgContext.currentOrgId()
                .orElseGet(() -> request.remoteAddress()
                        .map(address -> address.getAddress().getHostAddress())
                        .orElse("unknown"));
    }
}
