package io.pallet.gitintegration.repolink;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.session.GitHubUserSessionStore;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.ReadModelFixtures;
import io.pallet.gitintegration.support.Tokens;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, Tokens.LocalDecoder.class})
class RepoLinkConcurrencyIntegrationTest {

    private static final int CALLERS = 10;
    private static final long REPO = 6161;

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @DynamicPropertySource
    static void github(DynamicPropertyRegistry registry) {
        GitHubApiStub.register(registry);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GitHubUserSessionStore sessions;

    private ReadModelFixtures fixtures;
    private String admin;

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanUp();
        if (admin != null) {
            sessions.delete(admin);
            jdbc.update("DELETE FROM git_integration.authorization_states WHERE user_id = ?", admin);
        }
    }

    @Test
    void concurrentLinksForOneAppCreateOneLinkAndCleanConflicts() throws Exception {
        String orgId = fixtures.newOrg();
        long installationId = fixtures.newInstallation();
        fixtures.linkInstallation(installationId, orgId, "ACTIVE");
        UUID appId = fixtures.newApp(orgId, "ACTIVE");
        admin = fixtures.newMember(orgId, "admin", "ACTIVE");
        new RepoLinkApi(mvc, json, github).as(admin).signIn();
        github.stubRepository(REPO, installationId, "admin", false);
        github.stubTokenMint(installationId);
        github.stubBranch(REPO, "main", "d".repeat(40));

        CountDownLatch start = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> calls = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(CALLERS)) {
            for (int i = 0; i < CALLERS; i++) {
                RepoLinkApi caller = new RepoLinkApi(mvc, json, github).as(admin);
                calls.add(pool.submit(() -> {
                    start.await();
                    return caller.link(orgId, appId, Map.of("installationId", installationId, "repoId", REPO));
                }));
            }
            start.countDown();
            List<MockHttpServletResponse> responses = new ArrayList<>();
            for (Future<MockHttpServletResponse> call : calls) {
                responses.add(call.get(60, TimeUnit.SECONDS));
            }

            Map<Integer, Long> byStatus = responses.stream()
                    .collect(Collectors.groupingBy(MockHttpServletResponse::getStatus, Collectors.counting()));
            assertThat(byStatus).containsExactlyInAnyOrderEntriesOf(Map.of(201, 1L, 409, (long) CALLERS - 1));
            assertThat(responses.stream().filter(response -> response.getStatus() == 409))
                    .allSatisfy(conflict ->
                            assertThat(conflict.getContentAsString()).contains("\"REPO_LINK_EXISTS\""));
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM git_integration.repo_links WHERE app_id = ? AND status = 'ACTIVE'",
                        Integer.class,
                        appId))
                .isOne();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM git_integration.branch_heads WHERE app_id = ?", Integer.class, appId))
                .isOne();
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM git_integration.outbox_events
                         WHERE org_id = ? AND payload ->> 'action' = 'git.repo_link.created'
                        """, Integer.class, orgId)).isOne();
    }
}
