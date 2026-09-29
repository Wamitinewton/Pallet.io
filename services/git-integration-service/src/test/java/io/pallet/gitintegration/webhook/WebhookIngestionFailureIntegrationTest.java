package io.pallet.gitintegration.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import io.pallet.common.error.ErrorResponse;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.assertions.PalletAssertions;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.support.WebhookFixtures;
import io.pallet.gitintegration.support.WebhookFixtures.Delivery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import(RedisTestContainerConfiguration.class)
@TestPropertySource(
        properties = {
            "spring.datasource.hikari.connection-timeout=1500",
            "spring.datasource.hikari.validation-timeout=500"
        })
class WebhookIngestionFailureIntegrationTest {

    /** Past Hikari's 500 ms window in which a recently returned connection is handed out without validation. */
    private static final long IDLE_PAST_ALIVE_BYPASS_MS = 1_000;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void anUnreachableDatabaseIs503AndTheSameDeliveryIsStoredOnceItIsBack() throws Exception {
        Delivery push = WebhookFixtures.delivery("push-main.json");
        DockerClient docker = postgres.getDockerClient();

        docker.pauseContainerCmd(postgres.getContainerId()).exec();
        MockHttpServletResponse duringOutage;
        try {
            Thread.sleep(IDLE_PAST_ALIVE_BYPASS_MS);
            duringOutage = push.post(mvc);
        } finally {
            docker.unpauseContainerCmd(postgres.getContainerId()).exec();
        }

        assertThat(duringOutage.getStatus()).isEqualTo(503);
        ErrorResponse error = jsonMapper.readValue(duringOutage.getContentAsString(), ErrorResponse.class);
        PalletAssertions.assertThat(error).isFailure().hasStatusCode(503).hasErrorCode("SERVICE_UNAVAILABLE");

        assertThat(push.post(mvc).getStatus()).isEqualTo(202);
        assertThat(jdbc.queryForObject(
                        "select status from git_integration.webhook_deliveries where delivery_id = ?::uuid",
                        String.class,
                        push.deliveryId()))
                .isEqualTo("RECEIVED");
        jdbc.update("delete from git_integration.webhook_deliveries where delivery_id = ?::uuid", push.deliveryId());
    }
}
