package io.pallet.build;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.build.config.BuildProperties;
import io.pallet.build.config.BuildPropertiesValidator;
import io.pallet.common.error.ErrorResponse;
import io.pallet.common.outbox.OutboxMetrics;
import io.pallet.common.outbox.OutboxProperties;
import io.pallet.common.outbox.OutboxRelay;
import io.pallet.common.security.RedisRevokedSessionRegistry;
import io.pallet.common.security.RevokedSessionRegistry;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.assertions.PalletAssertions;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class BuildServiceApplicationIntegrationTest {

    private static final List<String> TABLES = List.of(
            "apps",
            "orgs",
            "org_memberships",
            "build_settings",
            "builds",
            "build_steps",
            "build_log_chunks",
            "observer_state",
            "outbox_events",
            "processed_events");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RevokedSessionRegistry revokedSessionRegistry;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private WebEndpointsSupplier webEndpoints;

    @Test
    void flywayAppliesV1AndEveryTableExists() {
        assertThat(jdbc.queryForObject(
                        "select count(*) from build.flyway_schema_history where version = '1' and success",
                        Integer.class))
                .isOne();
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables"
                        + " where table_schema = 'build' and table_name <> 'flyway_schema_history'",
                String.class);
        assertThat(tables).containsExactlyInAnyOrderElementsOf(TABLES);
    }

    @Test
    void theObserverStateStartsWithItsOneReachableRow() {
        assertThat(jdbc.queryForList("select unreachable_since, recovered_at from build.observer_state"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("unreachable_since")).isNull();
                    assertThat(row.get("recovered_at")).isNull();
                });
    }

    @Test
    void theConfigurationIsBoundAndValidated() {
        BuildProperties properties = context.getBean(BuildProperties.class);

        assertThat(properties.execution().namespace()).isEqualTo("pallet-builds");
        assertThat(properties.deployed()).isFalse();
        assertThat(context.getBeansOfType(BuildPropertiesValidator.class)).hasSize(1);
        assertThat(context.getBean(Clock.class)).isNotNull();
    }

    @Test
    void theRevocationRegistryIsTheRedisBackedOne() {
        assertThat(revokedSessionRegistry).isInstanceOf(RedisRevokedSessionRegistry.class);
    }

    @Test
    void theOutboxRelayRunsUnderTheBuildMetricsPrefix() {
        assertThat(context.getBeansOfType(OutboxRelay.class)).hasSize(1);
        assertThat(context.getBean(OutboxProperties.class).metricsPrefix()).isEqualTo("build");
        assertThat(meterRegistry.find("build." + OutboxMetrics.PENDING).gauge()).isNotNull();
    }

    @Test
    void healthIsUpAndPublic() throws Exception {
        HttpResponse<String> health = get("/actuator/health");

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(jsonMapper.readTree(health.body()).path("status").asString()).isEqualTo("UP");
    }

    @Test
    void theActuatorExposesOnlyHealthAndPrometheusOverHttp() throws Exception {
        assertThat(webEndpoints.getEndpoints())
                .extracting(endpoint -> endpoint.getEndpointId().toString())
                .containsExactlyInAnyOrder("health", "prometheus");
        assertThat(get("/actuator/prometheus").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/env").statusCode()).isNotEqualTo(200);
    }

    @Test
    void theApiNeedsAToken() throws Exception {
        HttpResponse<String> unauthenticated = get("/api/v1/build/anything");

        assertThat(unauthenticated.statusCode()).isEqualTo(401);
        ErrorResponse body = jsonMapper.readValue(unauthenticated.body(), ErrorResponse.class);
        PalletAssertions.assertThat(body).isFailure().hasStatusCode(401);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
