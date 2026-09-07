package io.pallet.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Times {@link Monitored} methods. Ordered {@link Ordered#LOWEST_PRECEDENCE} so it wraps any
 * resilience or idempotency advice on the same method and measures the real user-visible latency,
 * retries included.
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE)
public class MonitoringAspect {

    static final String DEFAULT_METRIC = "pallet.method.duration";

    private final MeterRegistry registry;

    public MonitoringAspect(MeterRegistry registry) {
        this.registry = registry;
    }

    @Around("@annotation(monitored)")
    public Object time(ProceedingJoinPoint pjp, Monitored monitored) throws Throwable {
        Timer.Sample sample = Timer.start(registry);
        String exception = "none";
        String outcome = "success";
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            outcome = "failure";
            exception = t.getClass().getSimpleName();
            throw t;
        } finally {
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            String name = monitored.value().isBlank() ? DEFAULT_METRIC : monitored.value();
            sample.stop(Timer.builder(name)
                    .tag("class", signature.getDeclaringType().getSimpleName())
                    .tag("method", signature.getName())
                    .tag("outcome", outcome)
                    .tag("exception", exception)
                    .register(registry));
        }
    }
}
