package io.pallet.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.events.OrgMembershipChanged;
import io.pallet.common.events.Topics;
import io.pallet.common.test.annotations.UnitTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@UnitTest
class OutboxWriterTest {

    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final Tracer tracer = mock(Tracer.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<Tracer> tracerProvider = mock(ObjectProvider.class);

    private final OutboxWriter writer = new OutboxWriter(
            repository,
            OutboxEventTypes.of(OrgMemberAdded.class, OrgMembershipChanged.class),
            jsonMapper,
            tracerProvider);

    @BeforeEach
    void noTracer() {
        when(tracerProvider.getIfAvailable()).thenReturn(null);
    }

    @Test
    void appendsTheEventUnderItsOwnIdentity() {
        OrgMemberAdded event = OrgMemberAdded.of("org-1", "user-1", "a@example.com");

        writer.append(event);

        OutboxRow row = captureRow();
        assertThat(row.id()).isNull();
        assertThat(row.eventId()).isEqualTo(event.eventId());
        assertThat(row.orgId()).isEqualTo("org-1");
        assertThat(row.eventType()).isEqualTo(OrgMemberAdded.TYPE);
        assertThat(row.sensitive()).isFalse();
        assertThat(row.traceparent()).isNull();
        assertThat(row.recordKey()).isNull();
        assertThat(row.tombstone()).isFalse();
        JsonNode payload = jsonMapper.readTree(row.payload());
        assertThat(payload.get("eventId").asString()).isEqualTo(event.eventId().toString());
        assertThat(payload.get("userId").asString()).isEqualTo("user-1");
    }

    @Test
    void carriesTheSensitiveFlagThrough() {
        writer.append(OrgMemberAdded.of("org-1", "user-1", "a@example.com"), true);

        assertThat(captureRow().sensitive()).isTrue();
    }

    @Test
    void capturesTheActiveSpanAsAW3cTraceparent() {
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracerProvider.getIfAvailable()).thenReturn(tracer);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("4bf92f3577b34da6a3ce929d0e0e4736");
        when(context.spanId()).thenReturn("00f067aa0ba902b7");
        when(context.sampled()).thenReturn(true);

        writer.append(OrgMemberAdded.of("org-1", "user-1", "a@example.com"));

        assertThat(captureRow().traceparent()).isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    }

    @Test
    void storesAnExplicitRecordKey() {
        OrgMembershipChanged event = OrgMembershipChanged.of("org-1", "user-1", "admin", "ACTIVE", 3);

        writer.append(event, OrgMembershipChanged.key("org-1", "user-1"));

        OutboxRow row = captureRow();
        assertThat(row.recordKey()).isEqualTo("org-1:user-1");
        assertThat(row.orgId()).isEqualTo("org-1");
        assertThat(row.tombstone()).isFalse();
    }

    @Test
    void refusesABlankRecordKey() {
        OrgMembershipChanged event = OrgMembershipChanged.of("org-1", "user-1", "admin", "ACTIVE", 3);

        assertThatThrownBy(() -> writer.append(event, " ")).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).insert(any());
    }

    @Test
    void appendsATombstoneWithAKeyAndNoPayload() {
        writer.appendTombstone("org-1", Topics.ORG_MEMBERSHIP_CHANGED, "org-1:user-1");

        OutboxRow row = captureRow();
        assertThat(row.tombstone()).isTrue();
        assertThat(row.payload()).isNull();
        assertThat(row.recordKey()).isEqualTo("org-1:user-1");
        assertThat(row.eventType()).isEqualTo(Topics.ORG_MEMBERSHIP_CHANGED);
        assertThat(row.eventId()).isNotNull();
    }

    @Test
    void refusesATombstoneForATopicThatIsNotCompacted() {
        assertThatThrownBy(() -> writer.appendTombstone("org-1", Topics.ORG_MEMBER_ADDED, "org-1:user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(Topics.ORG_MEMBER_ADDED);
        verify(repository, never()).insert(any());
    }

    @Test
    void refusesAnEventTypeTheServiceDidNotDeclare() {
        OutboxWriter undeclared = new OutboxWriter(repository, OutboxEventTypes.none(), jsonMapper, tracerProvider);

        assertThatThrownBy(() -> undeclared.append(OrgMemberAdded.of("org-1", "user-1", "a@example.com")))
                .isInstanceOf(UnknownEventTypeException.class);
        verify(repository, never()).insert(any());
    }

    private OutboxRow captureRow() {
        ArgumentCaptor<OutboxRow> captor = ArgumentCaptor.forClass(OutboxRow.class);
        verify(repository).insert(captor.capture());
        return captor.getValue();
    }
}
