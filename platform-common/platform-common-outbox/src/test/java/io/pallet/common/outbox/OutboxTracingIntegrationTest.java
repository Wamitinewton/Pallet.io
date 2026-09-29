package io.pallet.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.pallet.common.events.OrgMemberAdded;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

@OutboxIntegrationTest
class OutboxTracingIntegrationTest extends OutboxIntegrationSupport {

    private static final String TRACEPARENT = "00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]";

    @Autowired
    private ObjectProvider<Tracer> tracer;

    @Test
    void aRowAppendedInsideASpanCarriesATraceparentTheRelayParentsOn() {
        Tracer available = tracer.getObject();
        OrgMemberAdded event = memberAdded(newOrgId());
        Span request = available.nextSpan().name("request").start();
        try (Tracer.SpanInScope ignored = available.withSpan(request)) {
            commit(event);
        } finally {
            request.end();
        }

        String traceparent = traceparentOf(event.eventId());

        assertThat(traceparent).matches(TRACEPARENT);
        assertThat(traceparent)
                .contains(request.context().traceId())
                .contains(request.context().spanId());

        OutboxRelay relay = newRelay(repository, Clock.systemUTC(), tracer);
        relay.tick();

        assertThat(statusOf(event.eventId())).isEqualTo("PUBLISHED");
    }

    @Test
    void aRowAppendedOutsideASpanHasNoTraceparentAndStillPublishes() {
        OrgMemberAdded event = memberAdded(newOrgId());
        commit(event);

        assertThat(traceparentOf(event.eventId())).isNull();

        newRelay(repository, Clock.systemUTC(), tracer).tick();

        assertThat(statusOf(event.eventId())).isEqualTo("PUBLISHED");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "garbage",
                "00-not-hex-01",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331",
                "00-0AF7651916CD43DD8448EB211C80319C-B7AD6B7169203331-01",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b71692033-01",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01-extra"
            })
    void aMalformedTraceparentIsIgnoredRatherThanBackingOffTheRelay(String malformed) {
        OrgMemberAdded event = memberAdded(newOrgId());
        commit(event);
        jdbc.update("update " + OUTBOX + " set traceparent = ? where event_id = ?", malformed, event.eventId());
        OutboxRelay relay = newRelay(repository, Clock.systemUTC(), tracer);

        relay.tick();

        assertThat(statusOf(event.eventId())).isEqualTo("PUBLISHED");
        assertThat(attemptsOf(event.eventId())).isZero();
        assertThat(relay.brokerBackoff()).isZero();
    }

    private String traceparentOf(UUID eventId) {
        return jdbc.queryForObject("select traceparent from " + OUTBOX + " where event_id = ?", String.class, eventId);
    }
}
