package io.pallet.build.admission;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wakes admission when a build may be waiting for a slot. Postgres delivers a notification only when the sending
 * transaction commits, so admission never wakes for a build that rolled back.
 */
@Component
public class AdmissionSignal {

    public static final String CHANNEL = "build_admission";

    private final JdbcClient jdbc;

    AdmissionSignal(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void wakeAdmission() {
        jdbc.sql("SELECT pg_notify(:channel, '')")
                .param("channel", CHANNEL)
                .query()
                .listOfRows();
    }
}
