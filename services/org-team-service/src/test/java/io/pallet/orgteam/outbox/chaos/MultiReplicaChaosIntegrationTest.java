package io.pallet.orgteam.outbox.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.orgteam.outbox.RelayInstances;
import io.pallet.orgteam.outbox.RelayInstances.Replica;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Three relay replicas (the scheduled one plus two more) share one database. The advisory lock must
 * make them behave as one relay: no double publishing, no overlap, and instant failover.
 */
class MultiReplicaChaosIntegrationTest extends OutboxChaosSupport {

    private static final Duration REPLICA_POLL = Duration.ofMillis(20);

    private ScheduledExecutorService replicas;

    @BeforeEach
    void startExtraReplicas() {
        replicas = Executors.newScheduledThreadPool(2);
        for (int i = 0; i < 2; i++) {
            Replica replica = RelayInstances.create(context);
            replicas.scheduleWithFixedDelay(
                    () -> {
                        try {
                            replica.tick();
                        } catch (RuntimeException expectedUnderInjectedFaults) {
                            // a replica that loses its session simply polls again
                        }
                    },
                    0,
                    REPLICA_POLL.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    @AfterEach
    void stopExtraReplicas() throws InterruptedException {
        replicas.shutdownNow();
        replicas.awaitTermination(10, TimeUnit.SECONDS);
    }

    @Test
    void threeReplicasPublishEveryEventExactlyOnceAndNeverHoldTheLockTogether() throws Exception {
        long seed = ChaosSeed.next("threeReplicasPublishEveryEventExactlyOnceAndNeverHoldTheLockTogether");
        List<String> orgs = newOrgs(4);
        AtomicInteger maxHolders = new AtomicInteger();
        AtomicBoolean sampling = new AtomicBoolean(true);
        Thread sampler = Thread.ofPlatform().start(() -> {
            while (sampling.get()) {
                maxHolders.accumulateAndGet(postgres.relayLockHolders(), Math::max);
                Pauses.sleep(Duration.ofMillis(5));
            }
        });

        try (LoadGenerator load =
                new LoadGenerator(transaction, writer, ledger, orgs, 2, 0.1, Duration.ofMillis(15), seed)) {
            Pauses.sleep(Duration.ofSeconds(4));
        } finally {
            sampling.set(false);
            sampler.join();
        }

        awaitDrained(orgs);
        assertThat(ledger.totalCommitted()).as("the load actually ran").isGreaterThan(50);
        assertThat(maxHolders.get()).as("relays holding the lock at once").isLessThanOrEqualTo(1);
        OutboxInvariants.verifyAll(ledger, deliveries(), jdbc, consumer, 0);
    }

    @Test
    void killingWhicheverReplicaIsActiveHandsOverToAStandbyWithoutLoss() {
        long seed = ChaosSeed.next("killingWhicheverReplicaIsActiveHandsOverToAStandbyWithoutLoss");
        List<String> orgs = newOrgs(4);
        int kills = 0;

        try (LoadGenerator load =
                new LoadGenerator(transaction, writer, ledger, orgs, 2, 0.1, Duration.ofMillis(15), seed)) {
            for (int round = 0; round < 8; round++) {
                Pauses.sleep(Duration.ofMillis(400));
                kills += postgres.killRelayLockHolders();
            }
        }

        awaitDrained(orgs);
        assertThat(kills).as("an active replica was actually killed").isGreaterThanOrEqualTo(2);
        OutboxInvariants.verifyAll(
                ledger,
                deliveries(),
                jdbc,
                consumer,
                kills * properties.outbox().batchSize());
    }
}
