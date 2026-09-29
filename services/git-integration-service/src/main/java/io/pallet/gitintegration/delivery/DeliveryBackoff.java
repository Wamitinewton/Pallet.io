package io.pallet.gitintegration.delivery;

import io.pallet.gitintegration.config.GitIntegrationProperties;
import java.time.Duration;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Exponential backoff with full jitter: a delay drawn uniformly from {@code [0, min(base × 2^(n−1), max))}, so
 * deliveries that failed together don't all come back together.
 */
@Component
public class DeliveryBackoff {

    private static final int MAX_SHIFT = 30;

    private final Duration base;
    private final Duration max;
    private final RandomGenerator random;

    @Autowired
    DeliveryBackoff(GitIntegrationProperties properties) {
        this(properties.delivery().backoffBase(), properties.delivery().backoffMax(), RandomGenerator.getDefault());
    }

    public DeliveryBackoff(Duration base, Duration max, RandomGenerator random) {
        this.base = base;
        this.max = max;
        this.random = random;
    }

    /** The delay before retry number {@code attempts} ({@code attempts ≥ 1}), capped by the configured maximum. */
    public Duration next(int attempts) {
        return next(attempts, max);
    }

    /** As {@link #next(int)}, capped by {@code cap} instead. */
    public Duration next(int attempts, Duration cap) {
        return jitter(ceiling(base, attempts, cap));
    }

    /** A delay drawn uniformly from {@code [0, bound)}. */
    public Duration jitter(Duration bound) {
        return Duration.ofMillis((long) (bound.toMillis() * random.nextDouble()));
    }

    static Duration ceiling(Duration base, int attempts, Duration cap) {
        int shift = Math.clamp(attempts - 1L, 0, MAX_SHIFT);
        Duration grown = base.multipliedBy(1L << shift);
        return grown.compareTo(cap) > 0 ? cap : grown;
    }
}
