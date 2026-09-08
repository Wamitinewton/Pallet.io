package io.pallet.common.resilience;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The one way a Pallet service calls something outside its control. Every call to SMTP, the
 * Keycloak Admin API, GitHub, an ACME/DNS provider or a cloud API goes through here instead of
 * a hand-rolled retry loop or a bare {@code CircuitBreaker} annotation.
 */
public interface ExternalCall {

    /**
     * Runs {@code supplier} under the named policy: time-limited, retried on failure with
     * exponential backoff, and gated by a circuit breaker. Composition order (outermost to
     * innermost): circuit breaker, then retry, then time limiter, then {@code supplier}. Blocks
     * the caller.
     *
     * @param policy   a name from {@code pallet.resilience.policies}; an unconfigured name falls
     *                 back to {@code pallet.resilience.defaults}
     * @param supplier the external call
     * @throws io.pallet.common.error.AppException             unwrapped, as thrown by {@code supplier} —
     *                                                         never retried and never wrapped
     * @throws io.pallet.common.error.ExternalServiceException when the breaker is open, the call
     *                                                         times out, or every retry is
     *                                                         exhausted — the cause is the last
     *                                                         underlying failure
     */
    <T> T call(String policy, Supplier<T> supplier);

    /**
     * Same as {@link #call(String, Supplier)}, but {@code fallback} runs instead of throwing
     * {@link io.pallet.common.error.ExternalServiceException} when the guarded call ultimately
     * fails. An {@link io.pallet.common.error.AppException} from {@code supplier} still propagates
     * unwrapped, bypassing the fallback.
     */
    <T> T call(String policy, Supplier<T> supplier, Function<Throwable, T> fallback);

    /**
     * Void convenience over {@link #call(String, Supplier)}.
     */
    default void run(String policy, Runnable action) {
        call(policy, () -> {
            action.run();
            return null;
        });
    }
}
