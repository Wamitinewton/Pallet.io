package io.pallet.orgteam.outbox.chaos;

import io.pallet.orgteam.outbox.chaos.PostgresFaults.HeldLock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Injects one fault at a time from a seeded, shuffled deck, so every kind of fault is guaranteed to
 * land within a run and the whole sequence replays from the seed. Each strike undoes itself before the
 * next begins; stopping the monkey waits for the current strike to finish healing.
 */
final class ChaosMonkey implements AutoCloseable {

    enum Strike {
        BROKER_OUTAGE,
        KILL_RELAY_SESSION,
        FAIL_MARK_PUBLISHED,
        HOLD_RELAY_LOCK,
        POISON_ROW
    }

    private static final Duration MIN_GAP = Duration.ofMillis(150);
    private static final Duration MAX_GAP = Duration.ofMillis(600);
    private static final int KILL_ATTEMPTS = 20;

    private final Random random;
    private final BrokerFaults broker;
    private final PostgresFaults postgres;
    private final JdbcTemplate jdbc;
    private final String orgPrefix;
    private final List<String> orgs;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong failWindowMillis = new AtomicLong();
    private final Map<Strike, Integer> landed = Collections.synchronizedMap(new EnumMap<>(Strike.class));
    private volatile Thread thread;
    private volatile Throwable failure;

    ChaosMonkey(
            long seed,
            BrokerFaults broker,
            PostgresFaults postgres,
            JdbcTemplate jdbc,
            String orgPrefix,
            List<String> orgs) {
        this.random = new Random(seed);
        this.broker = broker;
        this.postgres = postgres;
        this.jdbc = jdbc;
        this.orgPrefix = orgPrefix;
        this.orgs = orgs;
    }

    void start() {
        running.set(true);
        thread = Thread.ofPlatform().name("chaos-monkey").start(this::run);
    }

    /** Stops striking, waits for the in-flight strike to heal, and rethrows anything that went wrong inside it. */
    void stop() {
        running.set(false);
        Thread current = thread;
        if (current != null) {
            try {
                current.join(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while stopping the monkey", e);
            }
            if (current.isAlive()) {
                throw new IllegalStateException("the chaos monkey did not stop");
            }
        }
        if (failure != null) {
            throw new IllegalStateException("the chaos monkey failed", failure);
        }
    }

    Map<Strike, Integer> landed() {
        synchronized (landed) {
            return new EnumMap<>(landed);
        }
    }

    int totalStrikes() {
        return landed().values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Upper bound on legitimate resends of already-acked rows. While marking fails, the relay resends the
     * head row once per poll. A relay whose lock session is killed finishes its whole batch while the standby
     * takes the same rows, so each kill can duplicate up to a batch. Every other strike strands a few rows.
     */
    int maxRedeliveries(Duration pollInterval, int batchSize) {
        int kills = landed().getOrDefault(Strike.KILL_RELAY_SESSION, 0);
        return (int) (failWindowMillis.get() / pollInterval.toMillis()) + kills * batchSize + totalStrikes() * 5;
    }

    private void run() {
        List<Strike> deck = new ArrayList<>(List.of(Strike.values()));
        try {
            while (running.get()) {
                Collections.shuffle(deck, random);
                for (Strike strike : deck) {
                    if (!running.get()) {
                        return;
                    }
                    strike(strike);
                    landed.merge(strike, 1, Integer::sum);
                    Pauses.sleep(between(MIN_GAP, MAX_GAP));
                }
            }
        } catch (RuntimeException | Error e) {
            failure = e;
        }
    }

    private void strike(Strike strike) {
        switch (strike) {
            case BROKER_OUTAGE -> broker.outage(between(Duration.ofMillis(500), Duration.ofMillis(2500)));
            case KILL_RELAY_SESSION -> killARelaySession();
            case FAIL_MARK_PUBLISHED -> {
                Duration window = between(Duration.ofMillis(300), Duration.ofMillis(900));
                try (Fault ignored =
                        postgres.failOutboxWrites(PostgresFaults.Statement.MARK_PUBLISHED, orgPrefix + "%")) {
                    Pauses.sleep(window);
                }
                failWindowMillis.addAndGet(window.toMillis());
            }
            case HOLD_RELAY_LOCK -> {
                try (HeldLock ignored = postgres.holdRelayLock()) {
                    Pauses.sleep(between(Duration.ofMillis(500), Duration.ofMillis(1500)));
                }
            }
            case POISON_ROW -> poisonAnOrgBriefly();
        }
    }

    private void killARelaySession() {
        for (int attempt = 0; attempt < KILL_ATTEMPTS && running.get(); attempt++) {
            if (postgres.killRelayLockHolders() > 0) {
                return;
            }
            Pauses.sleep(Duration.ofMillis(50));
        }
    }

    private void poisonAnOrgBriefly() {
        String orgId = orgs.get(random.nextInt(orgs.size()));
        UUID eventId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO org_team.outbox_events (event_id, org_id, event_type, payload) "
                        + "VALUES (?, ?, 'bogus.event', '{}'::jsonb)",
                eventId,
                orgId);
        try {
            Pauses.sleep(between(Duration.ofMillis(800), Duration.ofMillis(2000)));
        } finally {
            jdbc.update("DELETE FROM org_team.outbox_events WHERE event_id = ?", eventId);
        }
    }

    private Duration between(Duration min, Duration max) {
        return Duration.ofMillis(min.toMillis() + (long) (random.nextDouble() * (max.toMillis() - min.toMillis())));
    }

    @Override
    public void close() {
        stop();
    }
}
