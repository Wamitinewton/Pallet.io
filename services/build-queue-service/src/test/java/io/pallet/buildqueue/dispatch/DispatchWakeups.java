package io.pallet.buildqueue.dispatch;

import org.springframework.context.ApplicationContext;
import org.springframework.context.Lifecycle;

/**
 * The dispatch wakeup loop for tests that run it by hand. A context closed by {@code @DirtiesContext} stops the shared
 * containers with it, so a test that needs the loop starts it before each test and stops it after, and its cached
 * context never dispatches another class's builds.
 */
public final class DispatchWakeups {

    private DispatchWakeups() {}

    public static Lifecycle of(ApplicationContext context) {
        return context.getBean(DispatchWakeup.class);
    }
}
