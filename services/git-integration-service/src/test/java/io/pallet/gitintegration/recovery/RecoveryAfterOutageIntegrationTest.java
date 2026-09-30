package io.pallet.gitintegration.recovery;

import static io.pallet.gitintegration.recovery.RecoveryScenario.sha;
import static io.pallet.gitintegration.support.GitHubApiStub.LoggedDelivery.failed;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.delivery.DeliveryProcessor;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.GitHubApiStub.LoggedDelivery;
import io.pallet.gitintegration.support.WebhookFixtures;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, GitHubApiStub.Properties.class})
@TestPropertySource(
        properties = {
            "spring.datasource.hikari.connection-timeout=1500",
            "spring.datasource.hikari.validation-timeout=500"
        })
class RecoveryAfterOutageIntegrationTest {

    /** Past Hikari's 500 ms window in which a recently returned connection is handed out without validation. */
    private static final long IDLE_PAST_ALIVE_BYPASS_MS = 1_000;

    private static final String ROOT = sha('0');
    private static final String A = sha('a');
    private static final String B = sha('b');
    private static final String C = sha('c');
    private static final String D = sha('d');
    private static final String E = sha('e');
    private static final String F = sha('f');

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @Autowired
    private RedeliverySweeper sweeper;

    @Autowired
    private HeadReconciler reconciler;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeliveryProcessor processor;

    @Autowired
    private PostgreSQLContainer postgres;

    private RecoveryScenario scenario;
    private long installationId;
    private long repoId;
    private String orgId;
    private UUID appId;

    @BeforeEach
    void setUp() {
        scenario = new RecoveryScenario(jdbc, mvc, processor);
        installationId = scenario.fixtures.newInstallation();
        repoId = RecoveryScenario.newRepoId();
        orgId = scenario.orgOn(installationId);
        appId = scenario.linkApp(orgId, installationId, repoId, ROOT);
        github.stubTokenMint(installationId);
    }

    @AfterEach
    void tearDown() {
        scenario.cleanUp();
    }

    @Test
    void pushesLostToAnOutageAreRedeliveredAndALongerOutageIsCaughtUpByTheReconciler() throws Exception {
        List<WebhookFixtures.Delivery> pushes = List.of(
                scenario.push(installationId, repoId, ROOT, A),
                scenario.push(installationId, repoId, A, B),
                scenario.push(installationId, repoId, B, C));
        Map<Long, WebhookFixtures.Delivery> gitHubLog = new LinkedHashMap<>();
        List<LoggedDelivery> log = new ArrayList<>();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        DockerClient docker = postgres.getDockerClient();
        docker.pauseContainerCmd(postgres.getContainerId()).exec();
        try {
            Thread.sleep(IDLE_PAST_ALIVE_BYPASS_MS);
            for (int i = 0; i < pushes.size(); i++) {
                WebhookFixtures.Delivery push = pushes.get(i);
                assertThat(scenario.post(push).getStatus()).isEqualTo(503);
                long hookDeliveryId = 900L + i;
                gitHubLog.put(hookDeliveryId, push);
                log.addFirst(failed(hookDeliveryId, push.deliveryId(), now.minus(3L - i, ChronoUnit.MINUTES), "push"));
            }
        } finally {
            docker.unpauseContainerCmd(postgres.getContainerId()).exec();
        }
        github.stubHookDeliveries(log);
        github.stubRedeliveriesAccepted();

        RedeliverySweeper.Run sweep = sweeper.run().orElseThrow();

        assertThat(sweep.requested()).isEqualTo(3);
        List<UUID> redelivered = new ArrayList<>();
        for (long hookDeliveryId : github.redeliveryRequests()) {
            WebhookFixtures.Delivery original = gitHubLog.get(hookDeliveryId);
            assertThat(scenario.post(original).getStatus()).isEqualTo(202);
            redelivered.add(UUID.fromString(original.deliveryId()));
        }
        scenario.processAll(redelivered);

        assertThat(redelivered)
                .containsExactlyElementsOf(pushes.stream()
                        .map(push -> UUID.fromString(push.deliveryId()))
                        .toList());
        assertThat(redelivered)
                .allSatisfy(id -> assertThat(scenario.delivery(id)).containsEntry("status", "PROCESSED"));
        assertThat(commits(scenario.published(orgId))).containsExactly(A, B, C);
        assertThat(scenario.head(appId)).contains(C);

        github.stubHookDeliveries(List.of());
        github.stubBranch(repoId, "main", F);
        github.stubCompare(repoId, C, F, "ahead");

        assertThat(sweeper.run().orElseThrow().requested()).isZero();
        HeadReconciler.Run reconciled = reconciler.run().orElseThrow();

        assertThat(reconciled.pushesFound()).isEqualTo(1);
        assertThat(commits(scenario.published(orgId)))
                .containsExactly(A, B, C, F)
                .doesNotContain(D, E);
        assertThat(scenario.head(appId)).contains(F);
        assertThat(reconciler.run().orElseThrow().pushesFound()).isZero();
        assertThat(commits(scenario.published(orgId))).hasSize(4);
    }

    private static List<String> commits(List<JsonNode> events) {
        return events.stream().map(event -> event.path("commitSha").asString()).toList();
    }
}
