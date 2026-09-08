package io.pallet.common.observability;

import org.slf4j.MDC;

import java.util.Optional;

/**
 * The stable end-to-end request handle — the value a user reads off an error toast.
 *
 * <p>Distinct from {@code traceId} (Micrometer's, already in the MDC): a trace can be
 * resampled or reset at a boundary, the correlation id survives it.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private CorrelationId() {
    }

    /**
     * The correlation id on the calling thread's MDC, if one has been installed.
     */
    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }
}
