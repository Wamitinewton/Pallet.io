package io.pallet.gitintegration.installation;

import static io.pallet.gitintegration.installation.LifecycleScenario.sha;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.events.GitPushReceived;
import io.pallet.common.events.NotificationRequested;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.audit.AuditEvents;
import io.pallet.gitintegration.delivery.DeliveryProcessor;
import io.pallet.gitintegration.installation.LifecycleScenario.Notice;
import io.pallet.gitintegration.push.SkipRules;
import io.pallet.gitintegration.scm.ScmProvider;
import io.pallet.gitintegration.support.GitHubApiStub;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class LifecycleProcessorIntegrationTest {

    private static final long REPO = 42;
    private static final long OTHER_REPO = 43;
    private static final String ACCOUNT = "pallet-fixtures";

    /** What {@code ReadModelFixtures.newInstallation()} stores; a repository event carries no account of its own. */
    private static final String STORED_ACCOUNT = "acme";

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @DynamicPropertySource
    static void github(DynamicPropertyRegistry registry) {
        GitHubApiStub.register(registry);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeliveryProcessor processor;

    @Autowired
    private ConnectionTeardown teardown;

    @Autowired
    private ScmProvider scm;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private LifecycleScenario scenario;
    private long installationId;
    private String orgA;
    private String orgB;

    @BeforeEach
    void setUp() {
        scenario = new LifecycleScenario(jdbc, processor);
        installationId = scenario.fixtures.newInstallation();
        orgA = scenario.orgLinkedTo(installationId);
        orgB = scenario.orgLinkedTo(installationId);
    }

    @AfterEach
    void tearDown() {
        scenario.cleanUp();
    }

    @Test
    void createdRecordsAnUnusedInstallationAndLinksNoOrg() {
        long created = scenario.fixtures.newInstallationId();

        assertThat(scenario.deliver("installation-created.json", Map.of("/installation/id", created)))
                .containsEntry("status", "PROCESSED");

        assertThat(scenario.installation(created)).containsEntry("status", "ACTIVE");
        assertThat(scenario.installation(created).get("unused_since")).isNotNull();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM git_integration.installation_links WHERE installation_id = ?",
                        Integer.class,
                        created))
                .isZero();
    }

    @Test
    void deletedEndsEveryOrgsConnectionAndTellsEachOrgOnlyOfItsOwnAppsExactlyOnce() {
        UUID appA1 = scenario.fixtures.newRepoLink(orgA, installationId, REPO);
        UUID appA2 = scenario.fixtures.newRepoLink(orgA, installationId, OTHER_REPO);
        UUID appB = scenario.fixtures.newRepoLink(orgB, installationId, REPO);

        UUID delivery = scenario.store("installation-deleted.json", Map.of("/installation/id", installationId));
        assertThat(scenario.process(delivery)).containsEntry("status", "PROCESSED");

        assertThat(scenario.installation(installationId)).containsEntry("status", "DELETED");
        assertThat(scenario.linkStatus(installationId, orgA)).isEqualTo("UNLINKED");
        assertThat(scenario.linkStatus(installationId, orgB)).isEqualTo("UNLINKED");
        for (UUID appId : List.of(appA1, appA2, appB)) {
            assertThat(scenario.repoLink(appId))
                    .containsEntry("status", "DISCONNECTED")
                    .containsEntry("disconnect_reason", "INSTALLATION_DELETED");
        }
        assertThat(scenario.notices(orgA)).singleElement().satisfies(notice -> {
            assertConnectionLost(notice, "INSTALLATION_DELETED", "");
            assertThat(notice.dedupeKey()).isEqualTo("git-connection-lost:" + delivery + ":" + orgA + ":installation");
            assertThat(notice.appSlugs())
                    .containsExactlyInAnyOrder(scenario.slug(appA1), scenario.slug(appA2))
                    .isSorted();
            assertThat(notice.variables().path("settingsUrl").asString())
                    .isEqualTo(GitHubApiStub.baseUrl() + "/organizations/" + ACCOUNT + "/settings/installations/"
                            + installationId);
        });
        assertThat(scenario.notices(orgB))
                .singleElement()
                .satisfies(notice -> assertThat(notice.appSlugs()).containsExactly(scenario.slug(appB)));

        assertThat(scenario.reprocess(delivery)).containsEntry("status", "PROCESSED");

        assertThat(scenario.notices(orgA)).hasSize(1);
        assertThat(scenario.notices(orgB)).hasSize(1);
    }

    @Test
    void deletedForAnInstallationNeverSeenChangesNothing() {
        long unknown = scenario.fixtures.newInstallationId();

        assertThat(scenario.deliver("installation-deleted.json", Map.of("/installation/id", unknown)))
                .containsEntry("status", "IGNORED")
                .containsEntry("outcome_reason", LifecycleProcessor.UNKNOWN_INSTALLATION);

        assertThat(scenario.installations(unknown)).isZero();
    }

    @Test
    void suspendNotifiesEveryLinkedOrgAndStopsPushesUntilUnsuspend() {
        UUID appA = scenario.fixtures.newRepoLink(orgA, installationId, REPO);

        UUID suspend = scenario.store("installation-suspend.json", Map.of("/installation/id", installationId));
        assertThat(scenario.process(suspend)).containsEntry("status", "PROCESSED");

        assertThat(scenario.installation(installationId)).containsEntry("status", "SUSPENDED");
        assertThat(scenario.installation(installationId).get("suspended_at")).isNotNull();
        assertThat(scenario.linkStatus(installationId, orgA)).isEqualTo("ACTIVE");
        assertThat(scenario.repoLink(appA)).containsEntry("status", "ACTIVE");
        assertThat(scenario.notices(orgA)).singleElement().satisfies(notice -> {
            assertConnectionLost(notice, "INSTALLATION_SUSPENDED", "");
            assertThat(notice.appSlugs()).containsExactly(scenario.slug(appA));
        });
        assertThat(scenario.notices(orgB))
                .singleElement()
                .satisfies(notice -> assertThat(notice.appSlugs()).isEmpty());
        assertThat(scenario.audits(orgB, AuditEvents.INSTALLATION_SUSPENDED)).hasSize(1);
        assertThat(push(sha('a'), sha('b')))
                .containsEntry("status", "IGNORED")
                .containsEntry("outcome_reason", SkipRules.INSTALLATION_INACTIVE);

        assertThat(scenario.reprocess(suspend)).containsEntry("status", "PROCESSED");
        assertThat(scenario.notices(orgA)).hasSize(1);

        assertThat(scenario.deliver("installation-unsuspend.json", Map.of("/installation/id", installationId)))
                .containsEntry("status", "PROCESSED");

        assertThat(scenario.installation(installationId))
                .containsEntry("status", "ACTIVE")
                .containsEntry("suspended_at", null);
        assertThat(scenario.notices(orgA)).hasSize(1);
        assertThat(scenario.audits(orgA, AuditEvents.INSTALLATION_UNSUSPENDED)).hasSize(1);
        assertThat(push(sha('a'), sha('c'))).containsEntry("status", "PROCESSED");
        assertThat(scenario.events(orgA, GitPushReceived.TYPE)).isOne();
    }

    @Test
    void suspendOrUnsuspendForAnInstallationNeverSeenRecordsIt() {
        long suspended = scenario.fixtures.newInstallationId();
        long unsuspended = scenario.fixtures.newInstallationId();

        scenario.deliver("installation-suspend.json", Map.of("/installation/id", suspended));
        scenario.deliver("installation-unsuspend.json", Map.of("/installation/id", unsuspended));

        assertThat(scenario.installation(suspended)).containsEntry("status", "SUSPENDED");
        assertThat(scenario.installation(unsuspended)).containsEntry("status", "ACTIVE");
        assertThat(scenario.installation(unsuspended).get("unused_since")).isNotNull();
    }

    @Test
    void nothingMovesAnInstallationOutOfDeleted() {
        scenario.deliver("installation-deleted.json", Map.of("/installation/id", installationId));
        int noticesAfterDelete = scenario.notices(orgA).size();

        assertThat(scenario.deliver("installation-suspend.json", Map.of("/installation/id", installationId)))
                .containsEntry("status", "PROCESSED");
        assertThat(scenario.deliver("installation-unsuspend.json", Map.of("/installation/id", installationId)))
                .containsEntry("status", "PROCESSED");
        assertThat(scenario.deliver("installation-deleted.json", Map.of("/installation/id", installationId)))
                .containsEntry("status", "PROCESSED");

        assertThat(scenario.installation(installationId)).containsEntry("status", "DELETED");
        assertThat(scenario.notices(orgA)).hasSize(noticesAfterDelete);
    }

    @Test
    void newPermissionsAcceptedRefreshesThePermissions() {
        Map<String, Object> permissions = Map.of("checks", "write", "contents", "read", "metadata", "read");

        assertThat(scenario.deliver(
                        "installation-created.json",
                        Map.of(
                                "/action",
                                "new_permissions_accepted",
                                "/installation/id",
                                installationId,
                                "/installation/permissions",
                                permissions)))
                .containsEntry("status", "PROCESSED");

        assertThat((String) scenario.installation(installationId).get("permissions"))
                .contains("\"checks\": \"write\"", "\"contents\": \"read\"");
    }

    @Test
    void addedRepositoriesJoinTheReadModelWithoutABranchUntilTheNextSync() {
        scenario.repository(installationId, 700000002, "pallet-fixtures/old-name");

        assertThat(scenario.deliver("installation-repositories-added.json", Map.of("/installation/id", installationId)))
                .containsEntry("status", "PROCESSED");

        assertThat(scenario.repositories(installationId))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("full_name", "pallet-fixtures/worker")
                        .containsEntry("default_branch", "main"));

        scenario.deliver(
                "installation-repositories-added.json",
                Map.of(
                        "/installation/id",
                        installationId,
                        "/repositories_added",
                        List.of(Map.of("id", 700000003, "full_name", "pallet-fixtures/new", "private", false))));

        assertThat(scenario.repositories(installationId).get(1))
                .containsEntry("full_name", "pallet-fixtures/new")
                .containsEntry("default_branch", null)
                .containsEntry("is_private", false);
    }

    @Test
    void removedRepositoriesDisconnectEveryOrgsLinksAndTellEachOrgPerRepository() {
        UUID appA = scenario.fixtures.newRepoLink(orgA, installationId, REPO);
        UUID appB = scenario.fixtures.newRepoLink(orgB, installationId, REPO);
        UUID kept = scenario.fixtures.newRepoLink(orgA, installationId, OTHER_REPO);
        scenario.repository(installationId, REPO, "octo-org/api");
        scenario.repository(installationId, OTHER_REPO, "octo-org/web");

        UUID delivery = scenario.store(
                "installation-repositories-removed.json",
                Map.of(
                        "/installation/id",
                        installationId,
                        "/repositories_removed",
                        List.of(Map.of("id", REPO, "full_name", "octo-org/api", "private", true))));
        assertThat(scenario.process(delivery)).containsEntry("status", "PROCESSED");

        assertThat(scenario.repoLink(appA)).containsEntry("disconnect_reason", "REPOSITORY_ACCESS_REMOVED");
        assertThat(scenario.repoLink(appB)).containsEntry("disconnect_reason", "REPOSITORY_ACCESS_REMOVED");
        assertThat(scenario.repoLink(kept)).containsEntry("status", "ACTIVE");
        assertThat(scenario.linkStatus(installationId, orgA)).isEqualTo("ACTIVE");
        assertThat(scenario.repositories(installationId))
                .extracting(row -> row.get("repo_id"))
                .containsExactly(OTHER_REPO);
        assertThat(scenario.notices(orgA)).singleElement().satisfies(notice -> {
            assertConnectionLost(notice, "REPOSITORY_ACCESS_REMOVED", "octo-org/api");
            assertThat(notice.dedupeKey()).isEqualTo("git-connection-lost:" + delivery + ":" + orgA + ":" + REPO);
            assertThat(notice.appSlugs()).containsExactly(scenario.slug(appA));
        });
        assertThat(scenario.notices(orgB))
                .singleElement()
                .satisfies(notice -> assertThat(notice.appSlugs()).containsExactly(scenario.slug(appB)));
    }

    @Test
    void renamedAndTransferredUpdateTheNameInTheReadModelAndOnEveryLink() {
        UUID appA = scenario.fixtures.newRepoLink(orgA, installationId, REPO);
        UUID appB = scenario.fixtures.newRepoLink(orgB, installationId, REPO);
        scenario.repository(installationId, REPO, "octo-org/api");

        assertThat(scenario.deliver("repository-renamed.json", repository(REPO, "octo-org/api-v2")))
                .containsEntry("status", "PROCESSED");

        assertThat(scenario.repoLink(appA)).containsEntry("repo_full_name", "octo-org/api-v2");
        assertThat(scenario.repoLink(appB)).containsEntry("repo_full_name", "octo-org/api-v2");
        assertThat(scenario.repositories(installationId).getFirst()).containsEntry("full_name", "octo-org/api-v2");

        scenario.deliver("repository-transferred.json", repository(REPO, "new-owner/api-v2"));

        assertThat(scenario.repoLink(appA))
                .containsEntry("repo_full_name", "new-owner/api-v2")
                .containsEntry("status", "ACTIVE");
        assertThat(scenario.notices(orgA)).isEmpty();
    }

    @Test
    void aDeletedRepositoryDisconnectsItsLinksAndNotifies() {
        UUID appA = scenario.fixtures.newRepoLink(orgA, installationId, REPO);
        scenario.repository(installationId, REPO, "octo-org/api");

        assertThat(scenario.deliver("repository-deleted.json", repository(REPO, "octo-org/api")))
                .containsEntry("status", "PROCESSED");

        assertThat(scenario.repoLink(appA)).containsEntry("disconnect_reason", "REPOSITORY_DELETED");
        assertThat(scenario.repositories(installationId)).isEmpty();
        assertThat(scenario.notices(orgA))
                .singleElement()
                .satisfies(
                        notice -> assertConnectionLost(notice, "REPOSITORY_DELETED", "octo-org/api", STORED_ACCOUNT));
        assertThat(scenario.notices(orgB)).isEmpty();
    }

    @Test
    void archivingSetsAndUnarchivingClearsTheFlagAndLinksStay() {
        UUID appA = scenario.fixtures.newRepoLink(orgA, installationId, REPO);
        scenario.repository(installationId, REPO, "octo-org/api");

        scenario.deliver("repository-archived.json", repository(REPO, "octo-org/api"));
        assertThat(scenario.repositories(installationId).getFirst()).containsEntry("archived", true);
        assertThat(scenario.repoLink(appA)).containsEntry("status", "ACTIVE");

        Map<String, Object> unarchived = new HashMap<>(repository(REPO, "octo-org/api"));
        unarchived.put("/action", "unarchived");
        scenario.deliver("repository-archived.json", unarchived);
        assertThat(scenario.repositories(installationId).getFirst()).containsEntry("archived", false);
    }

    @Test
    void anyOtherActionIsIgnored() {
        assertThat(scenario.deliver(
                        "repository-archived.json",
                        Map.of("/action", "publicized", "/installation/id", installationId)))
                .containsEntry("status", "IGNORED")
                .containsEntry("outcome_reason", LifecycleProcessor.UNHANDLED_ACTION);
        assertThat(scenario.deliver(
                        "installation-repositories-added.json",
                        Map.of("/action", "renamed", "/installation/id", installationId)))
                .containsEntry("outcome_reason", LifecycleProcessor.UNHANDLED_ACTION);
    }

    @Test
    void aDeletedInstallationsTokensAreDroppedAfterCommitAndNotBefore() {
        github.stubTokenMint(installationId);
        github.stubInstallationRepositories();
        list();
        assertThat(github.verifyTokenMints(installationId)).isOne();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    teardown.installationDeleted(installationId);
                    throw new IllegalStateException("rolled back");
                }))
                .hasMessage("rolled back");
        list();
        assertThat(github.verifyTokenMints(installationId)).isOne();

        transaction.executeWithoutResult(status -> {
            teardown.installationDeleted(installationId);
            list();
            assertThat(github.verifyTokenMints(installationId)).isOne();
        });

        list();
        assertThat(github.verifyTokenMints(installationId)).isEqualTo(2);
    }

    private void list() {
        scm.installationRepositories(installationId, 1, GitHubApiStub.PAGE_SIZE, null);
    }

    private Map<String, Object> push(String before, String after) {
        return scenario.deliver(
                "push-main.json",
                Map.of(
                        "/installation/id", installationId,
                        "/repository/id", REPO,
                        "/before", before,
                        "/after", after,
                        "/head_commit/id", after));
    }

    private Map<String, Object> repository(long repoId, String fullName) {
        return Map.of("/installation/id", installationId, "/repository/id", repoId, "/repository/full_name", fullName);
    }

    private static void assertConnectionLost(Notice notice, String reason, String repository) {
        assertConnectionLost(notice, reason, repository, ACCOUNT);
    }

    private static void assertConnectionLost(Notice notice, String reason, String repository, String account) {
        assertThat(notice.type()).isEqualTo(ConnectionLostNotifier.NOTIFICATION_TYPE);
        assertThat(notice.audience()).isEqualTo(NotificationRequested.AUDIENCE_ORG_ADMINS);
        assertThat(notice.variables().path("reason").asString()).isEqualTo(reason);
        assertThat(notice.variables().path("repository").asString()).isEqualTo(repository);
        assertThat(notice.variables().path("accountLogin").asString()).isEqualTo(account);
    }
}
