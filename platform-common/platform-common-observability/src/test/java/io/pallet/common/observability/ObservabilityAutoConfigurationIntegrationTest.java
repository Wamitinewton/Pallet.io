package io.pallet.common.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ObservabilityAutoConfigurationIntegrationTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoints.web.exposure.include=prometheus")
class ObservabilityAutoConfigurationIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @LocalServerPort
    private int port;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.create("http://localhost:" + port);
    }

    @Test
    void autoConfigurationRegistersEveryPieceByClasspathPresence() {
        assertThat(context.getBeansOfType(MonitoringAspect.class)).hasSize(1);
        assertThat(context.getBeansOfType(CorrelationIdFilter.class)).hasSize(1);
        assertThat(context.getBeansOfType(CorrelationConsumerInterceptor.class)).hasSize(1);
    }

    @Test
    void responseCarriesCorrelationIdAndMethodTimerIsScraped() {
        ResponseEntity<String> ping = client.get().uri("/ping").retrieve().toEntity(String.class);
        assertThat(ping.getBody()).isEqualTo("pong");
        assertThat(ping.getHeaders().getFirst(CorrelationId.HEADER)).isNotBlank();

        String metrics = client.get().uri("/actuator/prometheus").retrieve().body(String.class);
        assertThat(metrics).contains("pallet_method_duration_seconds");
    }

    @Test
    void methodMetricsCanBeDisabledByProperty() {
        runner().withPropertyValues("pallet.observability.method-metrics-enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(MonitoringAspect.class));
    }

    @Test
    void nonWebContextGetsTheAspectButNotTheFilter() {
        runner().run(ctx -> {
            assertThat(ctx).hasSingleBean(MonitoringAspect.class);
            assertThat(ctx).doesNotHaveBean(CorrelationIdFilter.class);
        });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PalletObservabilityAutoConfiguration.class))
                .withBean(SimpleMeterRegistry.class);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @RestController
        static class PingController {

            private final Pinger pinger;

            PingController(Pinger pinger) {
                this.pinger = pinger;
            }

            @GetMapping("/ping")
            String ping() {
                return pinger.ping();
            }
        }

        @Component
        static class Pinger {

            @Monitored
            String ping() {
                return "pong";
            }
        }
    }
}
