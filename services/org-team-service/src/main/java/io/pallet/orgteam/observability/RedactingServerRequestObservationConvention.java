package io.pallet.orgteam.observability;

import io.micrometer.common.KeyValue;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerHttpObservationDocumentation.HighCardinalityKeyNames;
import org.springframework.http.server.observation.ServerRequestObservationContext;

class RedactingServerRequestObservationConvention extends DefaultServerRequestObservationConvention {

    RedactingServerRequestObservationConvention(String name) {
        super(name);
    }

    @Override
    protected KeyValue httpUrl(ServerRequestObservationContext context) {
        if (context.getCarrier() instanceof HttpServletRequest request) {
            return KeyValue.of(HighCardinalityKeyNames.HTTP_URL, PathRedactor.redact(request.getRequestURI()));
        }
        return super.httpUrl(context);
    }
}
