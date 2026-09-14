package io.pallet.apigateway.observability;

import io.pallet.common.observability.CorrelationId;
import io.pallet.common.observability.ObservabilityProperties;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Carries the correlation id {@link io.pallet.common.observability.CorrelationIdFilter} already
 * resolved into MDC for this request onto the outbound proxy call, so a caller who sends no
 * correlation id at all doesn't cause the gateway and the backend to mint two unrelated ids for
 * what is, end to end, the same request.
 */
@Component
public class CorrelationIdPropagationFilterFunction implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    private final ObservabilityProperties observabilityProperties;

    CorrelationIdPropagationFilterFunction(ObservabilityProperties observabilityProperties) {
        this.observabilityProperties = observabilityProperties;
    }

    @Override
    public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
        Optional<String> correlationId = CorrelationId.current();
        if (correlationId.isEmpty()) {
            return next.handle(request);
        }
        ServerRequest forwarded = ServerRequest.from(request)
                .header(observabilityProperties.correlationHeader(), correlationId.get())
                .build();
        return next.handle(forwarded);
    }
}
