package io.pallet.apigateway.resilience;

import io.pallet.apigateway.config.GatewayProperties;
import io.pallet.common.resilience.ResilienceProperties;
import io.pallet.common.resilience.ResilienceRegistries;
import java.net.URI;
import org.springframework.cloud.gateway.server.mvc.filter.CircuitBreakerFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.filter.RetryFilterFunctions;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Circuit-breaker and retry filters for every route, resolved against the shared
 * {@link ResilienceRegistries}/{@link ResilienceProperties} beans {@code platform-common-resilience}
 * already autoconfigures — never a second, gateway-local Resilience4j registry.
 */
@Configuration
public class GatewayResilienceConfiguration {

    private final ResilienceRegistries registries;
    private final ResilienceProperties resilienceProperties;

    GatewayResilienceConfiguration(ResilienceRegistries registries, ResilienceProperties resilienceProperties) {
        this.registries = registries;
        this.resilienceProperties = resilienceProperties;
    }

    public HandlerFilterFunction<ServerResponse, ServerResponse> circuitBreaker(
            String routeName, GatewayProperties.Route route) {
        String policy = policyName(routeName, route);
        // Spring Cloud's CircuitBreakerFactory resolves an id against whatever breaker/time-limiter
        // already exists under that name in the shared registries rather than its own defaults
        // (Resilience4JCircuitBreaker wraps every call in the same TimeLimiter it finds this way,
        // which is what actually enforces this route's timeout — see ARCHITECTURE.md's own caveat
        // that HandlerFunctions.http() gives a blocking proxy call no other async boundary), so
        // registering both here first is what makes this route's own policy numbers the ones used.
        registries.circuitBreaker(policy);
        registries.timeLimiter(policy);
        return CircuitBreakerFilterFunctions.circuitBreaker(
                policy, URI.create("forward:/gateway-fallback/" + routeName));
    }

    public HandlerFilterFunction<ServerResponse, ServerResponse> retry(
            String routeName, GatewayProperties.Route route) {
        int maxAttempts = resilienceProperties
                .resolve(policyName(routeName, route))
                .retry()
                .maxAttempts();
        return RetryFilterFunctions.retry(config -> config.setRetries(maxAttempts));
    }

    private static String policyName(String routeName, GatewayProperties.Route route) {
        return route.resiliencePolicy() != null ? route.resiliencePolicy() : routeName;
    }
}
