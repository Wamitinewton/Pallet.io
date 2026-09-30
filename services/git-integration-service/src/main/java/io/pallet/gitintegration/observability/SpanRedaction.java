package io.pallet.gitintegration.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * Runs on every observation as it stops, before the tracing handler copies its key values onto the span: URLs lose
 * their query string, and header, cookie and authorization attributes are dropped. Outbound GitHub calls are the
 * reason, since their URLs carry cursors and their requests carry tokens.
 */
public class SpanRedaction implements ObservationFilter {

    private static final Set<String> URL_KEYS = Set.of("http.url", "url.full", "http.target", "uri");
    private static final Set<String> DROPPED_FRAGMENTS = Set.of("header", "authorization", "cookie", "url.query");

    @Override
    public Observation.@NonNull Context map(Observation.Context context) {
        for (KeyValue keyValue : context.getLowCardinalityKeyValues()) {
            redact(keyValue)
                    .ifPresentOrElse(
                            context::addLowCardinalityKeyValue,
                            () -> context.removeLowCardinalityKeyValue(keyValue.getKey()));
        }
        for (KeyValue keyValue : context.getHighCardinalityKeyValues()) {
            redact(keyValue)
                    .ifPresentOrElse(
                            context::addHighCardinalityKeyValue,
                            () -> context.removeHighCardinalityKeyValue(keyValue.getKey()));
        }
        return context;
    }

    /** @return the attribute to keep, or empty to drop it */
    static Optional<KeyValue> redact(KeyValue keyValue) {
        String key = keyValue.getKey().toLowerCase(Locale.ROOT);
        if (DROPPED_FRAGMENTS.stream().anyMatch(key::contains)) {
            return Optional.empty();
        }
        if (!URL_KEYS.contains(key)) {
            return Optional.of(keyValue);
        }
        String value = keyValue.getValue();
        int query = value.indexOf('?');
        return Optional.of(query < 0 ? keyValue : KeyValue.of(keyValue.getKey(), value.substring(0, query)));
    }
}
