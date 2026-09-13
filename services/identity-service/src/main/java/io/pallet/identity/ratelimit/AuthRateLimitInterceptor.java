package io.pallet.identity.ratelimit;

import io.pallet.common.error.TooManyRequestsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rejects a burst against the endpoints {@code RateLimitWebConfiguration} maps this to. Keyed by
 * client address plus request path, so one endpoint's traffic never consumes another's budget and
 * one caller's abuse never throttles a different caller.
 */
class AuthRateLimitInterceptor implements HandlerInterceptor {

    private final AuthRateLimiter rateLimiter;

    AuthRateLimitInterceptor(AuthRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String key = request.getRemoteAddr() + ":" + request.getRequestURI();
        if (!rateLimiter.tryAcquire(key)) {
            throw new TooManyRequestsException("Too many requests - try again shortly");
        }
        return true;
    }
}
