package io.pallet.orgteam;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.security.RedisRevokedSessionRegistry;
import io.pallet.common.security.RevokedSessionRegistry;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerContainerFactory;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class OrgTeamServiceApplicationIntegrationTest {

    private static final List<String> TABLES = List.of(
            "organizations",
            "memberships",
            "invites",
            "teams",
            "team_members",
            "apps",
            "outbox_events",
            "processed_events");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @Autowired
    private RevokedSessionRegistry revokedSessionRegistry;

    @Autowired
    private KafkaListenerContainerFactory<?> kafkaListenerContainerFactory;

    @Test
    void flywayAppliesTheSchemaAndEveryTableExists() {
        Integer appliedV1 = jdbcTemplate.queryForObject(
                "select count(*) from org_team.flyway_schema_history where version = '1' and success = true",
                Integer.class);
        assertThat(appliedV1).isEqualTo(1);

        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'org_team'", String.class);
        assertThat(tables).containsAll(TABLES);
    }

    @Test
    void theRevocationRegistryIsTheRedisBackedOne() {
        assertThat(revokedSessionRegistry).isInstanceOf(RedisRevokedSessionRegistry.class);
    }

    @Test
    void clockAndMessagingContainerFactoryResolve() {
        assertThat(clock).isNotNull();
        assertThat(kafkaListenerContainerFactory).isNotNull();
    }

    @Test
    void healthIsPublicAndEverythingElseIsUnauthenticated() throws Exception {
        assertThat(status("/actuator/health")).isEqualTo(200);
        assertThat(status("/api/v1/org-team/orgs/x")).isEqualTo(401);
    }

    private int status(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }
}
