package io.pallet.common.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.pallet.common.error.AppException;
import io.pallet.common.error.ExternalServiceException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

public class ExternalCallExecutor implements ExternalCall {

    private static final String GENERIC_CLIENT_MESSAGE = "A dependency is temporarily unavailable";

    private final ResilienceRegistries registries;
    private final ExecutorService timeLimiterExecutor;

    public ExternalCallExecutor(ResilienceRegistries registries, ExecutorService timeLimiterExecutor) {
        this.registries = registries;
        this.timeLimiterExecutor = timeLimiterExecutor;
    }

    private static <T> T callUnchecked(Callable<T> callable) {
        try {
            return callable.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new CheckedCallFailure(e);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CheckedCallFailure && failure.getCause() != null ? failure.getCause() : failure;
    }

    @Override
    public <T> T call(String policy, Supplier<T> supplier) {
        return call(policy, supplier, null);
    }

    @Override
    public <T> T call(String policy, Supplier<T> supplier, Function<Throwable, T> fallback) {
        try {
            return guarded(policy, supplier).get();
        } catch (RuntimeException e) {
            Throwable cause = unwrap(e);
            if (cause instanceof AppException appException) {
                throw appException;
            }
            if (fallback != null) {
                return fallback.apply(cause);
            }
            throw new ExternalServiceException(GENERIC_CLIENT_MESSAGE, "policy '" + policy + "': " + cause, cause);
        }
    }

    private <T> Supplier<T> guarded(String policy, Supplier<T> supplier) {
        CircuitBreaker circuitBreaker = registries.circuitBreaker(policy);
        Retry retry = registries.retry(policy);
        TimeLimiter timeLimiter = registries.timeLimiter(policy);

        Callable<T> timeLimited =
                TimeLimiter.decorateFutureSupplier(timeLimiter, () -> timeLimiterExecutor.submit(supplier::get));
        Supplier<T> retried = Retry.decorateSupplier(retry, () -> callUnchecked(timeLimited));
        return CircuitBreaker.decorateSupplier(circuitBreaker, retried);
    }

    /**
     * Carries a checked failure (e.g. a timeout) from the time-limited {@link Callable} through
     * the {@link Supplier}-based retry/breaker chain, which can't declare checked exceptions.
     */
    private static final class CheckedCallFailure extends RuntimeException {
        CheckedCallFailure(Throwable cause) {
            super(cause);
        }
    }
}
