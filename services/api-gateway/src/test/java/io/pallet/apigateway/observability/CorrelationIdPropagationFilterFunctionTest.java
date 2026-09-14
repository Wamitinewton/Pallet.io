package io.pallet.apigateway.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.pallet.common.observability.CorrelationId;
import io.pallet.common.observability.ObservabilityProperties;
import io.pallet.common.test.annotations.UnitTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

@UnitTest
class CorrelationIdPropagationFilterFunctionTest {

    private static final ObservabilityProperties PROPERTIES =
            new ObservabilityProperties(true, true, CorrelationId.HEADER);

    @Mock
    private HandlerFunction<ServerResponse> next;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void forwardsTheMdcCorrelationIdAsAnOutboundHeaderWhenPresent() throws Exception {
        MDC.put(CorrelationId.MDC_KEY, "corr-123");
        ServerRequest request = serverRequest();
        when(next.handle(any())).thenReturn(ServerResponse.ok().build());

        new CorrelationIdPropagationFilterFunction(PROPERTIES).filter(request, next);

        ArgumentCaptor<ServerRequest> forwarded = ArgumentCaptor.forClass(ServerRequest.class);
        verify(next).handle(forwarded.capture());
        assertThat(forwarded.getValue().headers().firstHeader(CorrelationId.HEADER))
                .isEqualTo("corr-123");
    }

    @Test
    void passesTheRequestThroughUnmodifiedWhenMdcHasNoCorrelationId() throws Exception {
        ServerRequest request = serverRequest();
        when(next.handle(request)).thenReturn(ServerResponse.ok().build());

        new CorrelationIdPropagationFilterFunction(PROPERTIES).filter(request, next);

        verify(next).handle(request);
    }

    private static ServerRequest serverRequest() {
        return ServerRequest.create(new MockHttpServletRequest("GET", "/api/v1/identity/users/me"), List.of());
    }
}
