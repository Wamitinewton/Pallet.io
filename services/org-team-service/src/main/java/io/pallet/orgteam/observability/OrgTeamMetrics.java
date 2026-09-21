package io.pallet.orgteam.observability;

import static io.pallet.orgteam.observability.MetricsCatalog.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.pallet.common.events.OrgInviteRejected;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Counters incremented inside a transaction only move once it commits. */
@Component
public class OrgTeamMetrics {

    private static final List<String> REJECTION_REASONS = List.of(
            OrgInviteRejected.REASON_REVOKED,
            OrgInviteRejected.REASON_EXPIRED,
            OrgInviteRejected.REASON_UNKNOWN_INVITE,
            OrgInviteRejected.REASON_ORG_DELETED,
            OrgInviteRejected.REASON_ALREADY_ACCEPTED);

    private final MeterRegistry registry;

    public OrgTeamMetrics(MeterRegistry registry) {
        this.registry = registry;
        List.of(
                        INVITES_CREATED,
                        INVITES_RESENT,
                        INVITES_REVOKED,
                        INVITES_ACCEPTED,
                        INVITES_EXPIRED,
                        MEMBERS_ADDED,
                        MEMBERS_REMOVED,
                        MEMBERS_ROLE_CHANGED,
                        APPS_CREATED,
                        ORGS_DELETED,
                        AUTHZ_TOKEN_ROLE_DRIFT,
                        PROFILE_NOT_YET_PROJECTED)
                .forEach(registry::counter);
        REJECTION_REASONS.forEach(reason -> registry.counter(INVITES_REJECTED, TAG_REASON, reason));
        LISTENERS.forEach(listener -> {
            registry.counter(EVENTS_PROCESSED, TAG_LISTENER, listener);
            registry.counter(EVENTS_FAILED, TAG_LISTENER, listener);
            registry.counter(INBOX_DUPLICATES, TAG_LISTENER, listener);
        });
    }

    public void inviteCreated() {
        count(INVITES_CREATED);
    }

    public void inviteResent() {
        count(INVITES_RESENT);
    }

    public void inviteRevoked() {
        count(INVITES_REVOKED);
    }

    public void inviteAccepted() {
        count(INVITES_ACCEPTED);
    }

    public void inviteRejected(String reason) {
        count(INVITES_REJECTED, TAG_REASON, reason);
    }

    public void invitesExpired(long rows) {
        if (rows > 0) {
            afterCommit(() -> registry.counter(INVITES_EXPIRED).increment(rows));
        }
    }

    public void memberAdded() {
        count(MEMBERS_ADDED);
    }

    public void memberRemoved() {
        count(MEMBERS_REMOVED);
    }

    public void memberRoleChanged() {
        count(MEMBERS_ROLE_CHANGED);
    }

    public void appCreated() {
        count(APPS_CREATED);
    }

    public void orgDeleted() {
        count(ORGS_DELETED);
    }

    public void eventProcessed(String listener) {
        count(EVENTS_PROCESSED, TAG_LISTENER, listener);
    }

    public void eventDropped(String listener, String reason) {
        count(EVENTS_DROPPED, TAG_LISTENER, listener, TAG_REASON, reason);
    }

    public void inboxDuplicate(String listener) {
        registry.counter(INBOX_DUPLICATES, TAG_LISTENER, listener).increment();
    }

    public void profileNotYetProjected() {
        registry.counter(PROFILE_NOT_YET_PROJECTED).increment();
    }

    /** Runs {@code body}, counting a failed delivery when it throws. */
    public void countingFailures(String listener, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException failure) {
            registry.counter(EVENTS_FAILED, TAG_LISTENER, listener).increment();
            throw failure;
        }
    }

    public void authzDenied(String reason) {
        registry.counter(AUTHZ_DENIED, TAG_REASON, reason).increment();
    }

    public void tokenRoleDrift() {
        registry.counter(AUTHZ_TOKEN_ROLE_DRIFT).increment();
    }

    public void sweepRows(String sweep, long rows) {
        registry.counter(SWEEP_ROWS, TAG_SWEEP, sweep).increment(rows);
    }

    public void sweepFailure(String sweep) {
        registry.counter(SWEEP_FAILURES, TAG_SWEEP, sweep).increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void stopSweepTimer(Timer.Sample sample, String sweep) {
        sample.stop(registry.timer(SWEEP_DURATION, TAG_SWEEP, sweep));
    }

    private void count(String name, String... tags) {
        afterCommit(() -> registry.counter(name, tags).increment());
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
