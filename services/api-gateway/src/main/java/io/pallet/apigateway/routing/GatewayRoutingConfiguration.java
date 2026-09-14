package io.pallet.apigateway.routing;

import io.pallet.apigateway.config.GatewayProperties;
import io.pallet.apigateway.observability.CorrelationIdPropagationFilterFunction;
import io.pallet.apigateway.ratelimit.RateLimitFilterFunction;
import io.pallet.apigateway.resilience.GatewayResilienceConfiguration;
import org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration
class GatewayRoutingConfiguration {

    @Bean
    RouterFunction<ServerResponse> gatewayRoutes(
            GatewayProperties properties,
            GatewayResilienceConfiguration resilience,
            RateLimitFilterFunction rateLimit,
            CorrelationIdPropagationFilterFunction correlationIdPropagation) {
        RouterFunction<ServerResponse> routes = null;
        for (var entry : properties.routes().entrySet()) {
            RouterFunction<ServerResponse> routeFunction =
                    route(entry.getKey(), entry.getValue(), resilience, rateLimit, correlationIdPropagation);
            routes = routes == null ? routeFunction : routes.and(routeFunction);
        }
        return routes;
    }

    private RouterFunction<ServerResponse> route(
            String name,
            GatewayProperties.Route route,
            GatewayResilienceConfiguration resilience,
            RateLimitFilterFunction rateLimit,
            CorrelationIdPropagationFilterFunction correlationIdPropagation) {
        return GatewayRouterFunctions.route(name)
                .before(BeforeFilterFunctions.uri(route.uri()))
                .route(RequestPredicates.path(route.path()), HandlerFunctions.http())
                // Runs first: a rate-limited request must never consume a retry attempt or count
                // toward a circuit breaker's failure window.
                .filter(rateLimit)
                .filter((request, next) -> route.allowedMethods().contains(request.method())
                        ? next.handle(request)
                        : ServerResponse.status(HttpStatus.METHOD_NOT_ALLOWED).build())
                // After rate limiting (a rejected request never needs a propagated id on a call
                // that's never made) and before resilience, so a retried attempt reuses the same
                // id instead of each attempt racing CorrelationIdFilter for a fresh one.
                .filter(correlationIdPropagation)
                // Circuit breaker wraps retry, not the other way round, so a retry-exhausted
                // call still counts as one failure in the breaker's sliding window.
                .filter(resilience.circuitBreaker(name, route))
                .filter(resilience.retry(name, route))
                .build();
    }
}
