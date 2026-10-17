package io.pallet.buildqueue.dispatch;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wakes the dispatcher when there may be work for it. Postgres delivers a notification only when the sending
 * transaction commits, so the dispatcher never wakes for work that rolled back.
 */
@Component
public class DispatchSignal {

    public static final String CHANNEL = "build_queue_dispatch";

    private final JdbcClient jdbc;

    DispatchSignal(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyDispatcher() {
        jdbc.sql("SELECT pg_notify(:channel, '')")
                .param("channel", CHANNEL)
                .query()
                .listOfRows();
    }
}
