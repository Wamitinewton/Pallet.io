package io.pallet.apigateway.resilience;

import io.pallet.common.error.ErrorResponse;
import jakarta.servlet.RequestDispatcher;
import java.util.concurrent.TimeoutException;
import org.springframework.cloud.gateway.server.mvc.common.MvcUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Where a tripped circuit breaker forwards a request, per route — renders the same
 * {@link ErrorResponse} shape {@code GlobalExceptionHandler} produces everywhere else, so a caller
 * can't tell a gateway-originated failure from a backend-originated one by its shape.
 */
@Configuration
class GatewayFallbackHandler {

    private static final String UNAVAILABLE_MESSAGE = "A dependency is temporarily unavailable";

    @Bean
    RouterFunction<ServerResponse> gatewayFallbackRoutes() {
        return RouterFunctions.route(RequestPredicates.path("/gateway-fallback/*"), GatewayFallbackHandler::render);
    }

    private static ServerResponse render(ServerRequest request) {
        Throwable failure = MvcUtils.getAttribute(request, MvcUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        HttpStatus status = isTimeout(failure) ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.SERVICE_UNAVAILABLE;
        return ServerResponse.status(status)
                .body(ErrorResponse.of(status, UNAVAILABLE_MESSAGE, status.name(), originalPath(request)));
    }

    private static String originalPath(ServerRequest request) {
        Object forwardedFrom = request.servletRequest().getAttribute(RequestDispatcher.FORWARD_REQUEST_URI);
        return forwardedFrom instanceof String path ? path : request.path();
    }

    // The breaker's TimeLimiter (see GatewayResilienceConfiguration) throws TimeoutException
    // directly; walking the cause chain also catches a lower-level socket timeout should one
    // surface first.
    private static boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException
                    || cause.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
        }
        return false;
    }
}
