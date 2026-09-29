package io.pallet.gitintegration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.annotations.RepositoryTest;
import io.pallet.common.test.assertions.OutboxSchemaAssertions;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The constraints the design relies on, checked against the migrated schema. Each rejection is a test's last statement. */
@RepositoryTest
class SchemaConstraintsIntegrationTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void aVerifiedLinkOnTheOrgsOwnAppAndInstallationIsAccepted() {
        UUID appId = app("org-a");
        installationLinkedTo(1L, "org-a");

        assertThatCode(() -> activeLink(appId, "org-a", 1L)).doesNotThrowAnyException();
    }

    @Test
    void aRepoLinkForAnAppOfAnotherOrgIsRejected() {
        UUID otherOrgsApp = app("org-b");
        installationLinkedTo(1L, "org-a");

        assertRejected(() -> activeLink(otherOrgsApp, "org-a", 1L));
    }

    @Test
    void aRepoLinkThroughAnInstallationThisOrgHasNotLinkedIsRejected() {
        UUID appId = app("org-a");
        installationLinkedTo(1L, "org-b");

        assertRejected(() -> activeLink(appId, "org-a", 1L));
    }

    @Test
    void anActiveRepoLinkWithoutAVerifierIsRejected() {
        UUID appId = app("org-a");
        installationLinkedTo(1L, "org-a");

        assertRejected(() -> jdbc.update(
                "insert into git_integration.repo_links (app_id, org_id, installation_id, repo_id, repo_full_name,"
                        + " production_branch, status) values (?, 'org-a', 1, 10, 'acme/web', 'main', 'ACTIVE')",
                appId));
    }

    @Test
    void aDisconnectedRepoLinkWithoutAReasonIsRejected() {
        UUID appId = app("org-a");
        installationLinkedTo(1L, "org-a");

        assertRejected(() -> jdbc.update(
                "insert into git_integration.repo_links (app_id, org_id, installation_id, repo_id, repo_full_name,"
                        + " production_branch, status) values (?, 'org-a', 1, 10, 'acme/web', 'main', 'DISCONNECTED')",
                appId));
    }

    @Test
    void aReasonOnAnActiveRepoLinkIsRejected() {
        UUID appId = app("org-a");
        installationLinkedTo(1L, "org-a");
        activeLink(appId, "org-a", 1L);

        assertRejected(() -> jdbc.update(
                "update git_integration.repo_links set disconnect_reason = 'UNLINKED_BY_USER' where app_id = ?",
                appId));
    }

    @Test
    void anAbsoluteRootDirectoryIsRejected() {
        UUID appId = app("org-a");
        installationLinkedTo(1L, "org-a");
        activeLink(appId, "org-a", 1L);

        assertRejected(() -> rootDirectory(appId, "/abs"));
    }

    @Test
    void aRootDirectoryClimbingOutIsRejected() {
        UUID appId = app("org-a");
        installationLinkedTo(1L, "org-a");
        activeLink(appId, "org-a", 1L);

        assertRejected(() -> rootDirectory(appId, "a/../b"));
    }

    @Test
    void aRelativeRootDirectoryIsAccepted() {
        UUID appId = app("org-a");
        installationLinkedTo(1L, "org-a");
        activeLink(appId, "org-a", 1L);

        assertThatCode(() -> rootDirectory(appId, "apps/web")).doesNotThrowAnyException();
    }

    @Test
    void aDuplicateDeliveryIdIsRejected() {
        UUID deliveryId = UUID.randomUUID();
        delivery(deliveryId);

        assertRejected(() -> delivery(deliveryId));
    }

    @Test
    void anInstallStateWithoutAnOrgIsRejected() {
        assertRejected(() -> authorizationState("INSTALL", null));
    }

    @Test
    void anAuthorizeStateWithAnOrgIsRejected() {
        assertRejected(() -> authorizationState("AUTHORIZE", "org-a"));
    }

    @Test
    void anInstallStateWithAnOrgAndAnAuthorizeStateWithoutOneAreAccepted() {
        assertThatCode(() -> {
                    authorizationState("INSTALL", "org-a");
                    authorizationState("AUTHORIZE", null);
                })
                .doesNotThrowAnyException();
    }

    @Test
    void aNonHexHeadShaIsRejected() {
        UUID appId = app("org-a");
        installationLinkedTo(1L, "org-a");
        activeLink(appId, "org-a", 1L);

        assertRejected(() -> branchHead(appId, "main", "Z123456789abcdef0123456789abcdef01234567"));
    }

    @Test
    void anUppercaseHeadShaIsRejected() {
        UUID appId = app("org-a");
        installationLinkedTo(1L, "org-a");
        activeLink(appId, "org-a", 1L);

        assertRejected(() -> branchHead(appId, "main", SHA.toUpperCase(Locale.ROOT)));
    }

    @Test
    void deletingARepoLinkCascadesItsBranchHeads() {
        UUID appId = app("org-a");
        installationLinkedTo(1L, "org-a");
        activeLink(appId, "org-a", 1L);
        branchHead(appId, "main", SHA);
        branchHead(appId, "release", SHA);

        jdbc.update("delete from git_integration.repo_links where app_id = ?", appId);

        assertThat(jdbc.queryForObject(
                        "select count(*) from git_integration.branch_heads where app_id = ?", Integer.class, appId))
                .isZero();
    }

    @Test
    void anUnknownMembershipRoleIsRejected() {
        assertRejected(() -> jdbc.update(
                "insert into git_integration.org_memberships (org_id, user_id, role, status, source_version)"
                        + " values ('org-a', 'u1', 'OWNER', 'ACTIVE', 1)"));
    }

    @Test
    void theOutboxTablesMatchTheModulesReferenceSchema() {
        OutboxSchemaAssertions.assertMatchesReference(dataSource, "git_integration");
    }

    private UUID app(String orgId) {
        UUID appId = UUID.randomUUID();
        jdbc.update(
                "insert into git_integration.apps (app_id, org_id, slug, status) values (?, ?, ?, 'ACTIVE')",
                appId,
                orgId,
                "app-" + appId.toString().substring(0, 8));
        return appId;
    }

    private void installationLinkedTo(long installationId, String orgId) {
        jdbc.update(
                "insert into git_integration.installations (installation_id, account_id, account_login, account_type,"
                        + " repository_selection, status) values (?, 100, 'acme', 'Organization', 'selected', 'ACTIVE')"
                        + " on conflict do nothing",
                installationId);
        jdbc.update(
                "insert into git_integration.installation_links (installation_id, org_id, status, linked_by_user_id,"
                        + " linked_by_github_user_id) values (?, ?, 'ACTIVE', 'user-1', 5000)",
                installationId,
                orgId);
    }

    private void activeLink(UUID appId, String orgId, long installationId) {
        jdbc.update(
                "insert into git_integration.repo_links (app_id, org_id, installation_id, repo_id, repo_full_name,"
                        + " production_branch, status, verified_by_user_id, verified_github_user_id,"
                        + " verified_github_login, verified_permission, access_verified_at, access_checked_at)"
                        + " values (?, ?, ?, 10, 'acme/web', 'main', 'ACTIVE', 'user-1', 5000, 'octocat', 'push',"
                        + " now(), now())",
                appId,
                orgId,
                installationId);
    }

    private void rootDirectory(UUID appId, String rootDirectory) {
        jdbc.update("update git_integration.repo_links set root_directory = ? where app_id = ?", rootDirectory, appId);
    }

    private void branchHead(UUID appId, String branch, String sha) {
        jdbc.update(
                "insert into git_integration.branch_heads (app_id, branch, head_sha) values (?, ?, ?)",
                appId,
                branch,
                sha);
    }

    private void delivery(UUID deliveryId) {
        jdbc.update(
                "insert into git_integration.webhook_deliveries (delivery_id, event, status) values (?, 'push', 'RECEIVED')",
                deliveryId);
    }

    private void authorizationState(String purpose, String orgId) {
        jdbc.update(
                "insert into git_integration.authorization_states (nonce, purpose, user_id, org_id, expires_at)"
                        + " values (?, ?, 'user-1', ?, now() + interval '10 minutes')",
                UUID.randomUUID(),
                purpose,
                orgId);
    }

    private static void assertRejected(Runnable statement) {
        assertThatThrownBy(statement::run).isInstanceOf(DataIntegrityViolationException.class);
    }
}
