package io.pallet.gitintegration.installation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.installation.RepositorySync.Result;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.ReadModelFixtures;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
@Import({RedisTestContainerConfiguration.class, GitHubApiStub.Properties.class})
class RepositorySyncIntegrationTest {

    private static final String PATH = GitHubApiStub.INSTALLATION_REPOSITORIES_PATH;
    private static final String FIRST_ETAG = "W/\"first\"";
    private static final String SECOND_ETAG = "W/\"second\"";

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @Autowired
    private RepositorySync sync;

    @Autowired
    private JdbcTemplate jdbc;

    private ReadModelFixtures fixtures;
    private long installationId;

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
        installationId = fixtures.newInstallation();
        github.stubTokenMint(installationId);
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanUp();
    }

    @Test
    void theFirstSyncInsertsEveryRepositoryAndStoresTheEtag() {
        stubListing(GitHubApiStub.fixture("installation-repositories.json").withHeader("ETag", FIRST_ETAG));

        assertThat(sync.sync(installationId)).isEqualTo(Result.SYNCED);

        assertThat(rows()).extracting(row -> row.get("repo_id")).containsExactly(42L, 43L, 44L, 45L, 46L);
        assertThat(rows().get(3))
                .containsEntry("full_name", "octo-org/legacy")
                .containsEntry("default_branch", "master")
                .containsEntry("is_private", false)
                .containsEntry("archived", true);
        assertThat(cursor()).isEqualTo(FIRST_ETAG);
        LoggedRequest listed = github.requests(PATH).getFirst();
        assertThat(listed.getHeader("If-None-Match")).isNull();
        assertThat(github.mintRequests(installationId).getFirst().getBodyAsString())
                .contains("\"metadata\":\"read\"");
    }

    @Test
    void aNotModifiedListingChangesNothing() {
        stubListing(GitHubApiStub.fixture("installation-repositories.json").withHeader("ETag", FIRST_ETAG));
        sync.sync(installationId);
        List<Map<String, Object>> before = rows();
        github.server()
                .stubFor(get(urlPathEqualTo(PATH))
                        .atPriority(1)
                        .withHeader("If-None-Match", equalTo(FIRST_ETAG))
                        .willReturn(aResponse().withStatus(304).withHeader("ETag", FIRST_ETAG)));

        assertThat(sync.sync(installationId)).isEqualTo(Result.UNCHANGED);

        assertThat(rows()).isEqualTo(before);
        assertThat(github.requests(PATH).getLast().getHeader("If-None-Match")).isEqualTo(FIRST_ETAG);
        assertThat(cursor()).isEqualTo(FIRST_ETAG);
    }

    @Test
    void aChangedListingUpsertsWhatIsThereAndDeletesWhatIsNot() {
        stubListing(GitHubApiStub.fixture("installation-repositories.json").withHeader("ETag", FIRST_ETAG));
        sync.sync(installationId);
        github.server().resetMappings();
        github.stubTokenMint(installationId);
        stubListing(GitHubApiStub.json(listing(
                        3,
                        repository(42, "octo-org/api-v2", false),
                        repository(43, "octo-org/web", true),
                        repository(47, "octo-org/new", false)))
                .withHeader("ETag", SECOND_ETAG));

        assertThat(sync.sync(installationId)).isEqualTo(Result.SYNCED);

        List<Map<String, Object>> rows = rows();
        assertThat(rows).extracting(row -> row.get("repo_id")).containsExactly(42L, 43L, 47L);
        assertThat(rows.get(0)).containsEntry("full_name", "octo-org/api-v2");
        assertThat(rows.get(1)).containsEntry("archived", true);
        assertThat(cursor()).isEqualTo(SECOND_ETAG);
    }

    @Test
    void aListingLongerThanAPageIsReadToItsEndWithTheEtagOnlyOnTheFirstPage() {
        String[] fullPage = LongStream.rangeClosed(1_001, 1_100)
                .mapToObj(id -> repository(id, "octo-org/repo-" + id, false))
                .toArray(String[]::new);
        stubPage(1, GitHubApiStub.json(listing(101, fullPage)).withHeader("ETag", FIRST_ETAG));
        stubPage(2, GitHubApiStub.json(listing(101, repository(2_000, "octo-org/last", false))));

        assertThat(sync.sync(installationId)).isEqualTo(Result.SYNCED);

        assertThat(rows()).hasSize(101);
        assertThat(github.requests(PATH)).hasSize(2);
        assertThat(cursor()).isEqualTo(FIRST_ETAG);
    }

    @Test
    void aLowBudgetSkipsTheRun() {
        stubListing(GitHubApiStub.fixture("installation-repositories.json")
                .withHeader("x-ratelimit-remaining", "100")
                .withHeader(
                        "x-ratelimit-reset",
                        String.valueOf(Instant.now().plusSeconds(3_600).getEpochSecond())));
        assertThat(sync.sync(installationId)).isEqualTo(Result.SYNCED);

        assertThat(sync.sync(installationId)).isEqualTo(Result.SKIPPED_BUDGET);

        assertThat(github.requests(PATH)).hasSize(1);
    }

    @Test
    void aDeletedInstallationIsNotSynced() {
        jdbc.update(
                "UPDATE git_integration.installations SET status = 'DELETED' WHERE installation_id = ?",
                installationId);
        stubListing(GitHubApiStub.fixture("installation-repositories.json"));

        assertThat(sync.sync(installationId)).isEqualTo(Result.SKIPPED_INACTIVE);

        assertThat(github.requests(PATH)).isEmpty();
        assertThat(rows()).isEmpty();
    }

    private void stubListing(ResponseDefinitionBuilder response) {
        github.server().stubFor(get(urlPathEqualTo(PATH)).willReturn(response));
    }

    private void stubPage(int page, ResponseDefinitionBuilder response) {
        github.server()
                .stubFor(get(urlPathEqualTo(PATH))
                        .withQueryParam("page", equalTo(String.valueOf(page)))
                        .willReturn(response));
    }

    private static String listing(int total, String... repositories) {
        return "{\"total_count\":" + total + ",\"repositories\":[" + String.join(",", repositories) + "]}";
    }

    private static String repository(long id, String fullName, boolean archived) {
        return "{\"id\":" + id + ",\"full_name\":\"" + fullName + "\",\"default_branch\":\"main\","
                + "\"private\":true,\"archived\":" + archived + "}";
    }

    private List<Map<String, Object>> rows() {
        return jdbc.queryForList("""
                SELECT repo_id, full_name, default_branch, is_private, archived, synced_at
                  FROM git_integration.installation_repositories
                 WHERE installation_id = ? ORDER BY repo_id
                """, installationId);
    }

    private String cursor() {
        List<String> found = jdbc.queryForList(
                "SELECT cursor FROM git_integration.sync_cursors WHERE name = ?",
                String.class,
                "repo-sync:" + installationId);
        return found.isEmpty() ? null : found.getFirst();
    }
}
