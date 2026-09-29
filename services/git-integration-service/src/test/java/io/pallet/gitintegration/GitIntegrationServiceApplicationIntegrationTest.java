package io.pallet.gitintegration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.error.ErrorResponse;
import io.pallet.common.outbox.OutboxMetrics;
import io.pallet.common.outbox.OutboxProperties;
import io.pallet.common.outbox.OutboxRelay;
import io.pallet.common.security.RedisRevokedSessionRegistry;
import io.pallet.common.security.RevokedSessionRegistry;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.assertions.PalletAssertions;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.support.TestSecrets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class GitIntegrationServiceApplicationIntegrationTest {

    private static final List<String> TABLES = List.of(
            "installations",
            "installation_links",
            "installation_repositories",
            "apps",
            "org_memberships",
            "deleted_orgs",
            "repo_links",
            "branch_heads",
            "webhook_deliveries",
            "authorization_states",
            "check_runs",
            "manual_build_requests",
            "sync_cursors",
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

    @Test
    void flywayAppliesV1AndEveryTableExists() {
        assertThat(jdbc.queryForObject(
                        "select count(*) from git_integration.flyway_schema_history where version = '1' and success",
                        Integer.class))
                .isOne();
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables"
                        + " where table_schema = 'git_integration' and table_name <> 'flyway_schema_history'",
                String.class);
        assertThat(tables).containsExactlyInAnyOrderElementsOf(TABLES);
    }

    @Test
    void theRevocationRegistryIsTheRedisBackedOne() {
        assertThat(revokedSessionRegistry).isInstanceOf(RedisRevokedSessionRegistry.class);
    }

    @Test
    void theOutboxRelayRunsUnderTheGitMetricsPrefix() {
        assertThat(context.getBeansOfType(OutboxRelay.class)).hasSize(1);
        assertThat(context.getBean(OutboxProperties.class).metricsPrefix()).isEqualTo("git");
        assertThat(meterRegistry.find("git." + OutboxMetrics.PENDING).gauge()).isNotNull();
        assertThat(meterRegistry.find("git." + OutboxMetrics.RELAY_ACTIVE).gauge())
                .isNotNull();
    }

    @Test
    void theClockAndTheValidatedAppKeyAreBeans() {
        assertThat(context.getBean(Clock.class)).isNotNull();
        assertThat(context.getBean(PrivateKey.class))
                .isInstanceOfSatisfying(
                        RSAPrivateKey.class,
                        key -> assertThat(key.getModulus())
                                .isEqualTo(((RSAPrivateKey) TestSecrets.APP_KEY.getPrivate()).getModulus()));
    }

    @Test
    void healthIsPublicAndTheApiNeedsAToken() throws Exception {
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);

        HttpResponse<String> unauthenticated = get("/api/v1/git-integration/anything");

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
