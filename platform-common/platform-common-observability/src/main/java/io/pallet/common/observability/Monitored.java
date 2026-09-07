package io.pallet.common.observability;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records a Micrometer timer for every call to the annotated method, tagged with
 * {@code outcome} and {@code exception}. Backed by {@link MonitoringAspect}.
 *
 * <p>Reserve it for the handful of methods worth their own metric — token issuance,
 * OTP verification, notification send — not every service method.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Monitored {

    /**
     * Timer name. Blank (the default) times under {@code pallet.method.duration} with
     * {@code class}/{@code method} tags; set this only to give a method its own named timer.
     */
    String value() default "";
}
