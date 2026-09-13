package io.pallet.identity.membership;

import io.pallet.common.resilience.ExternalCall;
import io.pallet.common.resilience.ExternalCallExecutor;
import io.pallet.common.resilience.ResilienceRegistries;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Overrides the production {@code ExternalCall} bean with a counting decorator around the same
 * real {@link ExternalCallExecutor} — {@code @ConditionalOnMissingBean} on the auto-configured
 * bean backs off once this one is registered, so every Keycloak admin call still goes through real
 * resilience wiring, just counted. Shared by all three membership-listener integration tests so
 * they resolve to the same cached Spring context instead of three separate ones, each holding its
 * own connection pool against the shared Postgres container.
 */
@TestConfiguration(proxyBeanMethods = false)
class MembershipListenerCallCounter {

    private static final String KEYCLOAK_ADMIN_POLICY = "keycloak-admin";

    @Bean
    AtomicInteger keycloakAdminCallCount() {
        return new AtomicInteger();
    }

    @Bean
    ExternalCall externalCall(
            ResilienceRegistries registries,
            ScheduledExecutorService palletResilienceScheduler,
            AtomicInteger keycloakAdminCallCount) {
        ExternalCall delegate = new ExternalCallExecutor(registries, palletResilienceScheduler);
        return new ExternalCall() {
            @Override
            public <T> T call(String policy, Supplier<T> supplier) {
                return call(policy, supplier, null);
            }

            @Override
            public <T> T call(String policy, Supplier<T> supplier, Function<Throwable, T> fallback) {
                if (KEYCLOAK_ADMIN_POLICY.equals(policy)) {
                    keycloakAdminCallCount.incrementAndGet();
                }
                return fallback == null ? delegate.call(policy, supplier) : delegate.call(policy, supplier, fallback);
            }
        };
    }
}
