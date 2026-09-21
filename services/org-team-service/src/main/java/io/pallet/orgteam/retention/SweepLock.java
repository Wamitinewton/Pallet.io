package io.pallet.orgteam.retention;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SweepLock {

    public enum Sweep {
        INVITE_EXPIRY(7305121501L, "invite-expiry"),
        OUTBOX_RETENTION(7305121502L, "outbox-retention"),
        INBOX_RETENTION(7305121503L, "inbox-retention"),
        TERMINAL_INVITES(7305121504L, "terminal-invites"),
        REMOVED_MEMBERSHIPS(7305121505L, "removed-memberships"),
        ORG_PURGE(7305121506L, "org-purge");

        private final long lockKey;
        private final String metricTag;

        Sweep(long lockKey, String metricTag) {
            this.lockKey = lockKey;
            this.metricTag = metricTag;
        }

        public String metricTag() {
            return metricTag;
        }
    }

    private final JdbcClient jdbc;

    SweepLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this transaction now holds the sweep's lock; it is released at commit or rollback */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryAcquire(Sweep sweep) {
        return jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)")
                .param("key", sweep.lockKey)
                .query(Boolean.class)
                .single();
    }
}
