package io.pallet.buildqueue.lease;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one-row {@code lease_sweeper_state}: when a sweep first found the backbone unreachable, or nothing while it is
 * reachable. It lives in Postgres, not memory, because whichever instance wins the next sweep has to know. Call under
 * the sweep's advisory lock.
 */
@Component
class LeaseSweeperState {

    private final JdbcClient jdbc;

    LeaseSweeperState(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this call began the outage, false if one was already recorded */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean markUnreachable() {
        return jdbc.sql("""
                        UPDATE build_queue.lease_sweeper_state SET unreachable_since = now()
                         WHERE id = 1 AND unreachable_since IS NULL
                        """).update() == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Instant> unreachableSince() {
        return jdbc.sql("SELECT unreachable_since FROM build_queue.lease_sweeper_state WHERE id = 1")
                .query((row, rowNumber) ->
                        Optional.ofNullable(row.getObject("unreachable_since", OffsetDateTime.class)))
                .single()
                .map(OffsetDateTime::toInstant);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void markReachable() {
        jdbc.sql("UPDATE build_queue.lease_sweeper_state SET unreachable_since = NULL WHERE id = 1")
                .update();
    }
}
