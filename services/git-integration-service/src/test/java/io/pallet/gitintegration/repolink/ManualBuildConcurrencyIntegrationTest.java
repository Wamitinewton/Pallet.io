package io.pallet.gitintegration.repolink;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.events.GitPushReceived;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.ReadModelFixtures;
import io.pallet.gitintegration.support.Tokens;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, GitHubApiStub.Properties.class, Tokens.LocalDecoder.class})
class ManualBuildConcurrencyIntegrationTest {

    private static final int CALLERS = 10;
    private static final long REPO = 7474;

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ReadModelFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanUp();
    }

    @Test
    void concurrentRequestsWithOneKeyBuildOnceAndAllGetTheSameAnswer() throws Exception {
        String orgId = fixtures.newOrg();
        long installationId = fixtures.newInstallation();
        fixtures.linkInstallation(installationId, orgId, "ACTIVE");
        UUID appId = fixtures.newRepoLink(orgId, installationId, REPO);
        String developer = fixtures.newMember(orgId, "developer", "ACTIVE");
        github.stubTokenMint(installationId);
        github.stubBranch(REPO, "main", "e".repeat(40));

        CountDownLatch start = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> calls = new ArrayList<>();
        List<MockHttpServletResponse> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(CALLERS)) {
            for (int i = 0; i < CALLERS; i++) {
                RepoLinkApi caller = new RepoLinkApi(mvc, json, github).as(developer);
                calls.add(pool.submit(() -> {
                    start.await();
                    return caller.build(orgId, appId, "same-key", null);
                }));
            }
            start.countDown();
            for (Future<MockHttpServletResponse> call : calls) {
                responses.add(call.get(60, TimeUnit.SECONDS));
            }
        }

        assertThat(responses)
                .allSatisfy(response -> assertThat(response.getStatus())
                        .as(response.getContentAsString())
                        .isEqualTo(202));
        assertThat(responses.stream()
                        .map(ManualBuildConcurrencyIntegrationTest::body)
                        .distinct())
                .hasSize(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM git_integration.outbox_events WHERE org_id = ? AND event_type = ?",
                        Integer.class,
                        orgId,
                        GitPushReceived.TYPE))
                .isOne();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM git_integration.manual_build_requests WHERE app_id = ?",
                        Integer.class,
                        appId))
                .isOne();
    }

    private static String body(MockHttpServletResponse response) {
        try {
            return response.getContentAsString();
        } catch (UnsupportedEncodingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
