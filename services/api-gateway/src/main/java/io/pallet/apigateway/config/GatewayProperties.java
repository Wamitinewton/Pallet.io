package io.pallet.apigateway.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

@ConfigurationProperties("pallet.gateway")
public record GatewayProperties(@DefaultValue Map<String, Route> routes) {

    public record Route(
            URI uri,
            String path,
            List<HttpMethod> allowedMethods,
            @DefaultValue List<String> publicPaths,
            String resiliencePolicy,
            @DefaultValue List<RateLimitOverride> rateLimits) {

        public Route {
            if (!rateLimits.isEmpty()) {
                PathPattern routePattern = PathPatternParser.defaultInstance.parse(path);
                for (RateLimitOverride override : rateLimits) {
                    if (!routePattern.matches(PathContainer.parsePath(override.path()))) {
                        throw new IllegalArgumentException(
                                "Rate-limit override " + override.path() + " is outside its route's path " + path);
                    }
                    if (override.mode() == RateLimitMode.DISABLED && !publicPaths.contains(override.path())) {
                        throw new IllegalArgumentException("Rate-limit override " + override.path()
                                + " is DISABLED but is not one of its route's public-paths");
                    }
                }
            }
        }
    }

    public record RateLimitOverride(
            String path, @DefaultValue("DEFAULT") RateLimitMode mode, Integer capacity, Duration window) {

        public RateLimitOverride {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("A rate-limit override needs a path");
            }
            if (mode == RateLimitMode.CUSTOM
                    && (capacity == null || capacity < 1 || window == null || !window.isPositive())) {
                throw new IllegalArgumentException(
                        "Rate-limit override " + path + " is CUSTOM and needs a positive capacity and window");
            }
        }
    }

    public enum RateLimitMode {
        DEFAULT,
        CUSTOM,
        DISABLED
    }

    public String[] allPublicPaths() {
        return routes.values().stream()
                .flatMap(route -> route.publicPaths().stream())
                .toArray(String[]::new);
    }
}
