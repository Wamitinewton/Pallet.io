package io.pallet.common.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitoringAspectTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private Sample sample;

    @BeforeEach
    void setUp() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new Sample());
        factory.addAspect(new MonitoringAspect(registry));
        sample = factory.getProxy();
    }

    @Test
    void successfulCallRecordsOneSuccessTimer() {
        sample.ok();

        var timer = registry.get(MonitoringAspect.DEFAULT_METRIC)
            .tag("class", "Sample").tag("method", "ok")
            .tag("outcome", "success").tag("exception", "none")
            .timer();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void throwingCallRecordsFailureAndPropagates() {
        assertThatThrownBy(sample::boom).isInstanceOf(IllegalStateException.class);

        var timer = registry.get(MonitoringAspect.DEFAULT_METRIC)
            .tag("outcome", "failure").tag("exception", "IllegalStateException")
            .timer();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void customValueNamesTheTimer() {
        sample.custom();

        assertThat(registry.get("sample.custom").timer().count()).isEqualTo(1);
        assertThat(registry.find(MonitoringAspect.DEFAULT_METRIC).timer()).isNull();
    }

    static class Sample {

        @Monitored
        String ok() {
            return "ok";
        }

        @Monitored
        void boom() {
            throw new IllegalStateException("boom");
        }

        @Monitored("sample.custom")
        String custom() {
            return "custom";
        }
    }
}
