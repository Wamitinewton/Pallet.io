package io.pallet.gitintegration.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.access.RepoAccessExceptions.BranchNotFoundException;
import io.pallet.gitintegration.access.RepoAccessExceptions.RepositoryArchivedException;
import io.pallet.gitintegration.access.RepoAccessExceptions.RepositoryNotAccessibleException;
import io.pallet.gitintegration.access.RepoAccessExceptions.RepositoryPermissionTooLowException;
import io.pallet.gitintegration.access.RepoAccessVerifier.VerifiedAccess;
import io.pallet.gitintegration.repolink.RepoLinkApi;
import io.pallet.gitintegration.session.GitHubAuthorizationService;
import io.pallet.gitintegration.session.GitHubUserSession;
import io.pallet.gitintegration.session.GitHubUserSessionStore;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.ReadModelFixtures;
import io.pallet.gitintegration.support.Tokens;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
class RepoAccessVerifierIntegrationTest {

    private static final long REPO = 5151;
    private static final String HEAD = "c".repeat(40);

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @DynamicPropertySource
    static void github(DynamicPropertyRegistry registry) {
        GitHubApiStub.register(registry);
    }

    @Autowired
    private RepoAccessVerifier verifier;

    @Autowired
    private GitHubAuthorizationService authorizations;

    @Autowired
    private GitHubUserSessionStore sessions;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ReadModelFixtures fixtures;
    private RepoLinkApi api;
    private String orgId;
    private String admin;
    private long installationId;
    private long accountId;
    private GitHubUserSession session;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new ReadModelFixtures(jdbc);
        api = new RepoLinkApi(mvc, json, github);
        orgId = fixtures.newOrg();
        admin = fixtures.newMember(orgId, "admin", "ACTIVE");
        installationId = fixtures.newInstallation();
        accountId = installationId;
        fixtures.linkInstallation(installationId, orgId, "ACTIVE");
        api.as(admin).signIn();
        session = authorizations.requireSession(admin);
        github.stubTokenMint(installationId);
        github.stubBranch(REPO, "main", HEAD);
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanUp();
        sessions.delete(admin);
        jdbc.update("DELETE FROM git_integration.authorization_states WHERE user_id = ?", admin);
    }

    @Test
    void aRepositoryHiddenFromTheCallerIsNotAccessible() {
        github.stubNotFound(GitHubApiStub.repositoryPath(REPO));

        assertThatThrownBy(() -> verify(null)).isInstanceOf(RepositoryNotAccessibleException.class);
        assertThat(github.verifyTokenMints(installationId)).isZero();
    }

    @Test
    void aRepositoryOfAnotherAccountIsNotAccessible() {
        github.stubRepository(REPO, accountId + 1, "admin", false);

        assertThatThrownBy(() -> verify(null)).isInstanceOf(RepositoryNotAccessibleException.class);
        assertThat(github.verifyTokenMints(installationId)).isZero();
    }

    @Test
    void anArchivedRepositoryIsRefused() {
        github.stubRepository(REPO, accountId, "admin", true);

        assertThatThrownBy(() -> verify(null)).isInstanceOf(RepositoryArchivedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pull", "triage"})
    void lessThanPushIsTooLow(String permission) {
        github.stubRepository(REPO, accountId, permission, false);

        assertThatThrownBy(() -> verify(null)).isInstanceOf(RepositoryPermissionTooLowException.class);
        assertThat(github.verifyTokenMints(installationId)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"push,PUSH", "maintain,MAINTAIN", "admin,ADMIN"})
    void pushOrAboveVerifiesWithTheHighestPermissionAndTheCallersGitHubUser(String held, RepoPermission recorded) {
        github.stubRepository(REPO, accountId, held, false);

        VerifiedAccess access = verify(null);

        assertThat(access.permission()).isEqualTo(recorded);
        assertThat(access.githubUserId()).isEqualTo(session.githubUserId());
        assertThat(access.githubLogin()).isEqualTo(session.githubLogin());
        assertThat(access.fullName()).isEqualTo("octo-org/repo-" + REPO);
        assertThat(access.branch()).isEqualTo("main");
        assertThat(access.headSha()).isEqualTo(HEAD);
    }

    @Test
    void aRepositoryTheCallerSeesButTheInstallationDoesntReachIsNotAccessible() {
        github.stubRepository(REPO, accountId, "admin", false);
        github.stubMintRefused(installationId);

        assertThatThrownBy(() -> verify(null)).isInstanceOf(RepositoryNotAccessibleException.class);
        assertThat(github.requests(GitHubApiStub.branchPath(REPO, "main"))).isEmpty();
    }

    @Test
    void aMissingBranchIsReported() {
        github.stubRepository(REPO, accountId, "push", false);
        github.stubNotFound(GitHubApiStub.branchPath(REPO, "feature/gone"));

        assertThatThrownBy(() -> verify("feature/gone")).isInstanceOf(BranchNotFoundException.class);
    }

    @Test
    void theTwoWaysOfNotReachingARepositoryAnswerByteForByteAlike() throws Exception {
        UUID appId = fixtures.newApp(orgId, "ACTIVE");

        github.stubNotFound(GitHubApiStub.repositoryPath(REPO));
        MockHttpServletResponse callerCant = api.link(orgId, appId, installationId, REPO);

        github.stubRepository(REPO, accountId, "admin", false);
        github.stubMintRefused(installationId);
        MockHttpServletResponse installationCant = api.link(orgId, appId, installationId, REPO);

        github.stubRepository(REPO, accountId + 1, "admin", false);
        MockHttpServletResponse otherAccount = api.link(orgId, appId, installationId, REPO);

        api.assertError(callerCant, 403).hasErrorCode("REPOSITORY_NOT_ACCESSIBLE");
        assertThat(api.errorWithoutTimestamp(installationCant)).isEqualTo(api.errorWithoutTimestamp(callerCant));
        assertThat(api.errorWithoutTimestamp(otherAccount)).isEqualTo(api.errorWithoutTimestamp(callerCant));
        assertThat(installationCant.getHeaderNames()).isEqualTo(callerCant.getHeaderNames());
    }

    private VerifiedAccess verify(String branch) {
        return verifier.verify(admin, session, installationId, accountId, REPO, branch);
    }
}
