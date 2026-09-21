package io.pallet.orgteam.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.common.test.annotations.UnitTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@UnitTest
class OrgTeamMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OrgTeamMetrics metrics = new OrgTeamMetrics(registry);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private double count(String name, String... tags) {
        return registry.counter(name, tags).count();
    }

    @Test
    void everyDomainCounterExistsAtZeroBeforeItsFirstEvent() {
        assertThat(registry.find(MetricsCatalog.INVITES_CREATED).counter()).isNotNull();
        assertThat(registry.find(MetricsCatalog.INVITES_REJECTED)
                        .tag(MetricsCatalog.TAG_REASON, "REVOKED")
                        .counter())
                .isNotNull();
        MetricsCatalog.LISTENERS.forEach(listener -> assertThat(registry.find(MetricsCatalog.EVENTS_FAILED)
                        .tag(MetricsCatalog.TAG_LISTENER, listener)
                        .counter())
                .isNotNull());
    }

    @Test
    void outsideATransactionACounterMovesImmediately() {
        metrics.inviteCreated();

        assertThat(count(MetricsCatalog.INVITES_CREATED)).isEqualTo(1);
    }

    @Test
    void insideATransactionACounterWaitsForTheCommit() {
        TransactionSynchronizationManager.initSynchronization();

        metrics.memberRemoved();
        assertThat(count(MetricsCatalog.MEMBERS_REMOVED)).isZero();

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(count(MetricsCatalog.MEMBERS_REMOVED)).isEqualTo(1);
    }

    @Test
    void aRolledBackTransactionCountsNothing() {
        TransactionSynchronizationManager.initSynchronization();

        metrics.appCreated();
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization ->
                        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(count(MetricsCatalog.APPS_CREATED)).isZero();
    }

    @Test
    void aFailingListenerBodyIsCountedAndRethrown() {
        assertThatThrownBy(() -> metrics.countingFailures("org-provisioned", () -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count(MetricsCatalog.EVENTS_FAILED, MetricsCatalog.TAG_LISTENER, "org-provisioned"))
                .isEqualTo(1);
    }

    @Test
    void aSuccessfulListenerBodyIsNotCountedAsAFailure() {
        metrics.countingFailures("org-provisioned", () -> {});

        assertThat(count(MetricsCatalog.EVENTS_FAILED, MetricsCatalog.TAG_LISTENER, "org-provisioned"))
                .isZero();
    }

    @Test
    void expiredInvitesAreCountedByTheRowsSwept() {
        metrics.invitesExpired(3);
        metrics.invitesExpired(0);

        assertThat(count(MetricsCatalog.INVITES_EXPIRED)).isEqualTo(3);
    }
}
