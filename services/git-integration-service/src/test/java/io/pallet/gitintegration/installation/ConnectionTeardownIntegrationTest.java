package io.pallet.gitintegration.installation;

import static io.pallet.gitintegration.installation.LifecycleScenario.sha;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.audit.Actor;
import io.pallet.gitintegration.audit.AuditEvents;
import io.pallet.gitintegration.delivery.DeliveryProcessor;
import io.pallet.gitintegration.installation.TeardownResult.DisconnectedLink;
import io.pallet.gitintegration.repolink.RepoLink.DisconnectReason;
import io.pallet.gitintegration.security.AccessExceptions.InstallationNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class ConnectionTeardownIntegrationTest {

    private static final int RACE_ITERATIONS = 200;
    private static final long REPO = 42;
    private static final long OTHER_REPO = 43;

    @Autowired
    private ConnectionTeardown teardown;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeliveryProcessor processor;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private LifecycleScenario scenario;
    private long installationId;
    private String orgA;
    private String orgB;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
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
    void unlinkingEndsOnlyThatOrgsLinksAuditsEachAndLeavesTheInstallationInUse() {
        UUID appA = scenario.fixtures.newRepoLink(orgA, installationId, REPO);
        UUID appB = scenario.fixtures.newRepoLink(orgB, installationId, REPO);
        scenario.head(appA, sha('a'));

        TeardownResult result = inTransaction(() -> teardown.unlinkInstallation(
                orgA, installationId, DisconnectReason.INSTALLATION_UNLINKED, Actor.user("user-admin")));

        assertThat(result.disconnected())
                .containsExactly(new DisconnectedLink(orgA, appA, installationId, REPO, "octo-org/api"));
        assertThat(result.unlinkedOrgs()).containsExactly(orgA);
        assertThat(scenario.repoLink(appA))
                .containsEntry("status", "DISCONNECTED")
                .containsEntry("disconnect_reason", "INSTALLATION_UNLINKED");
        assertThat(scenario.repoLink(appA).get("disconnected_at")).isNotNull();
        assertThat(scenario.heads(appA)).isZero();
        assertThat(scenario.repoLink(appB)).containsEntry("status", "ACTIVE");
        assertThat(scenario.linkStatus(installationId, orgB)).isEqualTo("ACTIVE");
        assertThat(scenario.installation(installationId).get("unused_since")).isNull();
        assertThat(scenario.audits(orgA, AuditEvents.REPO_LINK_DISCONNECTED))
                .singleElement()
                .satisfies(audit -> {
                    assertThat(audit.path("actor").asString()).isEqualTo("user-admin");
                    assertThat(audit.path("context").path("reason").asString()).isEqualTo("INSTALLATION_UNLINKED");
                });
        assertThat(scenario.audits(orgB, AuditEvents.REPO_LINK_DISCONNECTED)).isEmpty();
    }

    @Test
    void unlinkingAnInstallationTheOrgHasNotLinkedFails() {
        inTransaction(() -> teardown.unlinkInstallation(
                orgA, installationId, DisconnectReason.INSTALLATION_UNLINKED, Actor.user("user-admin")));

        assertThatThrownBy(() -> inTransaction(() -> teardown.unlinkInstallation(
                        orgA, installationId, DisconnectReason.INSTALLATION_UNLINKED, Actor.user("user-admin"))))
                .isInstanceOf(InstallationNotFoundException.class);
    }

    @Test
    void aDeletedInstallationEndsEveryOrgsLinksOnceAndStopsCountingAsUnused() {
        UUID appA = scenario.fixtures.newRepoLink(orgA, installationId, REPO);
        UUID appB = scenario.fixtures.newRepoLink(orgB, installationId, OTHER_REPO);
        scenario.head(appB, sha('b'));

        TeardownResult result = inTransaction(() -> teardown.installationDeleted(installationId));

        assertThat(result.unlinkedOrgs()).containsExactlyInAnyOrder(orgA, orgB);
        assertThat(result.disconnected()).extracting(DisconnectedLink::appId).containsExactlyInAnyOrder(appA, appB);
        assertThat(scenario.installation(installationId))
                .containsEntry("status", "DELETED")
                .containsEntry("unused_since", null);
        assertThat(scenario.installation(installationId).get("deleted_at")).isNotNull();
        assertThat(scenario.linkStatus(installationId, orgA)).isEqualTo("UNLINKED");
        assertThat(scenario.linkStatus(installationId, orgB)).isEqualTo("UNLINKED");
        assertThat(scenario.repoLink(appA)).containsEntry("disconnect_reason", "INSTALLATION_DELETED");
        assertThat(scenario.repoLink(appB)).containsEntry("disconnect_reason", "INSTALLATION_DELETED");
        assertThat(scenario.heads(appB)).isZero();
        assertThat(scenario.audits(orgA, AuditEvents.REPO_LINK_DISCONNECTED))
                .singleElement()
                .satisfies(audit -> assertThat(audit.path("actor").asString()).isEqualTo("system"));
        assertThat(scenario.audits(orgB, AuditEvents.INSTALLATION_UNLINKED)).hasSize(1);

        assertThat(inTransaction(() -> teardown.installationDeleted(installationId)))
                .isEqualTo(TeardownResult.NONE);
        assertThat(scenario.audits(orgA, AuditEvents.REPO_LINK_DISCONNECTED)).hasSize(1);
    }

    @Test
    void anUnknownInstallationDeletesNothing() {
        long unknown = scenario.fixtures.newInstallationId();

        assertThat(inTransaction(() -> teardown.installationDeleted(unknown))).isEqualTo(TeardownResult.NONE);
        assertThat(scenario.installations(unknown)).isZero();
    }

    @Test
    void removedRepositoriesEndEveryOrgsLinksToThemAndNothingElse() {
        UUID appA = scenario.fixtures.newRepoLink(orgA, installationId, REPO);
        UUID appB = scenario.fixtures.newRepoLink(orgB, installationId, REPO);
        UUID other = scenario.fixtures.newRepoLink(orgA, installationId, OTHER_REPO);

        TeardownResult result = inTransaction(
                () -> teardown.repositoriesRemoved(installationId, Set.of(REPO), DisconnectReason.REPOSITORY_DELETED));

        assertThat(result.disconnected()).extracting(DisconnectedLink::appId).containsExactlyInAnyOrder(appA, appB);
        assertThat(result.unlinkedOrgs()).isEmpty();
        assertThat(result.appsByRepositoryAndOrg().get(REPO)).containsOnlyKeys(orgA, orgB);
        assertThat(scenario.repoLink(appA)).containsEntry("disconnect_reason", "REPOSITORY_DELETED");
        assertThat(scenario.repoLink(appB)).containsEntry("disconnect_reason", "REPOSITORY_DELETED");
        assertThat(scenario.repoLink(other)).containsEntry("status", "ACTIVE");
        assertThat(scenario.linkStatus(installationId, orgA)).isEqualTo("ACTIVE");
        assertThat(scenario.installation(installationId).get("unused_since")).isNull();
    }

    @Test
    void aDeletedAppLosesOnlyItsOwnLinkAndOnlyOnce() {
        UUID deleted = scenario.fixtures.newRepoLink(orgA, installationId, REPO);
        UUID kept = scenario.fixtures.newRepoLink(orgA, installationId, REPO);
        scenario.head(deleted, sha('c'));

        TeardownResult result = inTransaction(() -> teardown.appDeleted(orgA, deleted));

        assertThat(result.disconnected()).extracting(DisconnectedLink::appId).containsExactly(deleted);
        assertThat(scenario.repoLink(deleted)).containsEntry("disconnect_reason", "APP_DELETED");
        assertThat(scenario.heads(deleted)).isZero();
        assertThat(scenario.repoLink(kept)).containsEntry("status", "ACTIVE");
        assertThat(inTransaction(() -> teardown.appDeleted(orgA, deleted))).isEqualTo(TeardownResult.NONE);
        assertThat(inTransaction(() -> teardown.appDeleted(orgB, kept))).isEqualTo(TeardownResult.NONE);
        assertThat(scenario.repoLink(kept)).containsEntry("status", "ACTIVE");
    }

    @Test
    void aDeletedOrgEndsItsOwnConnectionsAndStartsTheClockOnlyWhereNoOrgRemains() {
        long onlyOrgA = scenario.fixtures.newInstallation();
        scenario.fixtures.linkInstallation(onlyOrgA, orgA, "ACTIVE");
        UUID sharedA = scenario.fixtures.newRepoLink(orgA, installationId, REPO);
        UUID ownA = scenario.fixtures.newRepoLink(orgA, onlyOrgA, OTHER_REPO);
        UUID sharedB = scenario.fixtures.newRepoLink(orgB, installationId, REPO);

        TeardownResult result = inTransaction(() -> teardown.orgDeleted(orgA));

        assertThat(result.disconnected()).extracting(DisconnectedLink::appId).containsExactlyInAnyOrder(sharedA, ownA);
        assertThat(scenario.repoLink(sharedA)).containsEntry("disconnect_reason", "ORG_DELETED");
        assertThat(scenario.repoLink(ownA)).containsEntry("disconnect_reason", "ORG_DELETED");
        assertThat(scenario.repoLink(sharedB)).containsEntry("status", "ACTIVE");
        assertThat(scenario.linkStatus(installationId, orgA)).isEqualTo("UNLINKED");
        assertThat(scenario.linkStatus(onlyOrgA, orgA)).isEqualTo("UNLINKED");
        assertThat(scenario.linkStatus(installationId, orgB)).isEqualTo("ACTIVE");
        assertThat(scenario.installation(installationId).get("unused_since")).isNull();
        assertThat(scenario.installation(onlyOrgA).get("unused_since")).isNotNull();
        assertThat(scenario.installation(onlyOrgA)).containsEntry("status", "ACTIVE");
        assertThat(scenario.audits(orgA, AuditEvents.INSTALLATION_UNLINKED)).hasSize(2);

        assertThat(inTransaction(() -> teardown.orgDeleted(orgA))).isEqualTo(TeardownResult.NONE);
    }

    @Test
    void everyMethodRefusesToRunOutsideATransaction() {
        assertThatThrownBy(() -> teardown.installationDeleted(installationId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void teardownsRacingOnOneInstallationNeverDeadlock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < RACE_ITERATIONS; i++) {
                long installation = scenario.fixtures.newInstallation();
                String first = scenario.orgLinkedTo(installation);
                String second = scenario.orgLinkedTo(installation);
                UUID firstApp = scenario.fixtures.newRepoLink(first, installation, REPO);
                scenario.fixtures.newRepoLink(first, installation, OTHER_REPO);
                scenario.fixtures.newRepoLink(second, installation, REPO);
                List<Supplier<TeardownResult>> pair =
                        switch (i % 4) {
                            case 0 ->
                                List.of(
                                        () -> teardown.installationDeleted(installation),
                                        () -> teardown.orgDeleted(first));
                            case 1 ->
                                List.of(
                                        () -> teardown.repositoriesRemoved(
                                                installation, Set.of(REPO), DisconnectReason.REPOSITORY_ACCESS_REMOVED),
                                        () -> teardown.unlinkInstallation(
                                                second,
                                                installation,
                                                DisconnectReason.INSTALLATION_UNLINKED,
                                                Actor.SYSTEM));
                            case 2 ->
                                List.of(
                                        () -> teardown.appDeleted(first, firstApp),
                                        () -> teardown.installationDeleted(installation));
                            default -> List.of(() -> teardown.orgDeleted(second), () -> teardown.orgDeleted(first));
                        };
                race(pool, pair);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void race(ExecutorService pool, List<Supplier<TeardownResult>> pair) throws Exception {
        CyclicBarrier start = new CyclicBarrier(pair.size());
        List<Future<TeardownResult>> running = new ArrayList<>();
        for (Supplier<TeardownResult> work : pair) {
            running.add(pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return inTransaction(work);
            }));
        }
        for (Future<TeardownResult> future : running) {
            future.get(30, TimeUnit.SECONDS);
        }
    }

    private TeardownResult inTransaction(Supplier<TeardownResult> work) {
        return transaction.execute(status -> work.get());
    }
}
