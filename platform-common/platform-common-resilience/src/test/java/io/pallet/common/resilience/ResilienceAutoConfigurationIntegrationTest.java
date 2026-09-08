package io.pallet.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.pallet.common.error.ExternalServiceException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@SpringBootTest(
        classes = ResilienceAutoConfigurationIntegrationTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.endpoints.web.exposure.include=prometheus",
            "pallet.resilience.policies.smtp.retry.max-attempts=1"
        })
class ResilienceAutoConfigurationIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ExternalCall externalCall;

    @LocalServerPort
    private int port;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.create("http://localhost:" + port);
    }

    @Test
    void autoConfigurationRegistersTheRegistriesAndExternalCall() {
        assertThat(context.getBeansOfType(ExternalCall.class)).hasSize(1);
        assertThat(context.getBeansOfType(ResilienceRegistries.class)).hasSize(1);
        assertThat(context.getBeansOfType(CircuitBreakerRegistry.class)).hasSize(1);
        assertThat(context.getBeansOfType(RetryRegistry.class)).hasSize(1);
        assertThat(context.getBeansOfType(TimeLimiterRegistry.class)).hasSize(1);
    }

    @Test
    void breakerStateIsScrapedAfterAGuardedCall() {
        externalCall.call("probe-x", () -> "ok");

        String metrics = client.get().uri("/actuator/prometheus").retrieve().body(String.class);
        assertThat(metrics).contains("resilience4j_circuitbreaker_state");
    }

    @Test
    void aNamedPolicyOverridesTheDefaultRetryCount() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> externalCall.call("smtp", () -> {
                    attempts.incrementAndGet();
                    throw new RuntimeException("smtp down");
                }))
                .isInstanceOf(ExternalServiceException.class);

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void aServiceSuppliedExternalCallOverridesTheAutoConfiguredOne() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PalletResilienceAutoConfiguration.class))
                .withUserConfiguration(StubExternalCallConfiguration.class)
                .run(ctx -> assertThat(ctx.getBean(ExternalCall.class)).isInstanceOf(StubExternalCall.class));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    @Configuration(proxyBeanMethods = false)
    static class StubExternalCallConfiguration {

        @Bean
        ExternalCall externalCall() {
            return new StubExternalCall();
        }
    }

    private static final class StubExternalCall implements ExternalCall {

        @Override
        public <T> T call(String policy, Supplier<T> supplier) {
            return supplier.get();
        }

        @Override
        public <T> T call(String policy, Supplier<T> supplier, Function<Throwable, T> fallback) {
            return supplier.get();
        }
    }
}
