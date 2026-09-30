package io.pallet.gitintegration.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.GitIntegrationServiceApplication;
import io.pallet.gitintegration.support.Outage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@IntegrationTest
@AutoConfigureMockMvc
@Import(RedisTestContainerConfiguration.class)
@TestPropertySource(
        properties = {
            "spring.datasource.hikari.connection-timeout=1500",
            "spring.datasource.hikari.validation-timeout=500"
        })
class HealthIntegrationTest {

    private static final String READINESS = "/actuator/health/readiness";
    private static final String LIVENESS = "/actuator/health/liveness";
    private static final long IN_FLIGHT_MS = 1_500;
    private static final int REDIS_PORT = 6379;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private GenericContainer<?> redisContainer;

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    void readinessStaysUpWithKafkaAndRedisFrozenAndGoesDownOnlyWithPostgres() throws Exception {
        assertThat(status(READINESS)).isEqualTo(200);

        Outage.during(
                kafka,
                () -> Outage.during(redisContainer, () -> {
                    assertThat(status(READINESS))
                            .as("readiness with Kafka and Redis frozen")
                            .isEqualTo(200);
                    assertThat(status(LIVENESS)).isEqualTo(200);
                }));

        Outage.during(postgres, () -> {
            Thread.sleep(Outage.IDLE_PAST_ALIVE_BYPASS_MS);
            assertThat(status(READINESS)).as("readiness with Postgres frozen").isEqualTo(503);
            assertThat(status(LIVENESS)).as("liveness is the process only").isEqualTo(200);
        });

        assertThat(status(READINESS)).isEqualTo(200);
    }

    /**
     * Shuts down a second instance of the service, started against the same containers, so the cached test context
     * stays usable.
     */
    @Test
    void shutdownRefusesTrafficFirstAndLetsARunningScheduledTaskFinishWhileTheDatabaseIsStillOpen() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        ConfigurableApplicationContext instance = new SpringApplicationBuilder(GitIntegrationServiceApplication.class)
                .profiles("test")
                .properties(Map.of(
                        "server.port", "0",
                        "spring.datasource.url", postgres.getJdbcUrl(),
                        "spring.datasource.username", postgres.getUsername(),
                        "spring.datasource.password", postgres.getPassword(),
                        "spring.kafka.bootstrap-servers", kafka.getBootstrapServers(),
                        "spring.data.redis.host", redisContainer.getHost(),
                        "spring.data.redis.port", String.valueOf(redisContainer.getMappedPort(REDIS_PORT))))
                .listeners((ApplicationListener<AvailabilityChangeEvent<?>>) event -> {
                    if (event.getState() == ReadinessState.REFUSING_TRAFFIC) {
                        order.add("refusing traffic");
                    }
                })
                .run();
        JdbcTemplate instanceJdbc = instance.getBean(JdbcTemplate.class);
        CountDownLatch running = new CountDownLatch(1);
        instance.getBean(TaskScheduler.class)
                .schedule(
                        () -> {
                            running.countDown();
                            try {
                                Thread.sleep(IN_FLIGHT_MS);
                                instanceJdbc.queryForObject("SELECT 1", Integer.class);
                                order.add("in-flight task committed");
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                order.add("in-flight task interrupted");
                            }
                        },
                        Instant.now());
        assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();

        instance.close();

        assertThat(order).containsExactly("refusing traffic", "in-flight task committed");
    }

    private int status(String path) throws Exception {
        return mvc.perform(get(path)).andReturn().getResponse().getStatus();
    }
}
