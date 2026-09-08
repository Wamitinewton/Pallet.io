package io.pallet.common.events;

/**
 * Kafka header names carried with every record on the backbone.
 * <p>
 * {@code occurred-at} and the W3C {@code traceparent}. {@code org-id} (also the
 * record key) and {@code X-Correlation-Id} are here too so the messaging and
 * observability modules share one constants class. {@code traceparent} is
 * written by Micrometer's Kafka instrumentation, not stamped by hand — it is
 * listed so a consumer can read it.
 */
public final class EventHeaders {

    public static final String EVENT_ID = "event-id";
    public static final String EVENT_TYPE = "event-type";
    public static final String OCCURRED_AT = "occurred-at";
    public static final String ORG_ID = "org-id";
    public static final String TRACEPARENT = "traceparent";
    public static final String CORRELATION_ID = "X-Correlation-Id";
    private EventHeaders() {
    }
}
