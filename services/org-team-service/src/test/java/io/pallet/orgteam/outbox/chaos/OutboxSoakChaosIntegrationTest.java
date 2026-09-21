package io.pallet.orgteam.outbox.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.orgteam.outbox.chaos.ChaosMonkey.Strike;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Concurrent writers, several relay replicas, and a seeded monkey that lands every kind of fault
 * (broker outage, killed relay session, failing database writes, a held relay lock, a poison row)
 * in random order. After everything is healed the outbox must satisfy every guarantee at once.
 * Length is {@code -Dpallet.chaos.soak.seconds} (default 30); replay a failure with the printed seed.
 */
class OutboxSoakChaosIntegrationTest extends OutboxChaosSupport {

    private static final int SOAK_SECONDS = Integer.getInteger("pallet.chaos.soak.seconds", 30);
    private static final int WRITERS = 4;
    private static final String ORG_PREFIX = "chaos-soak";

    @Test
    void everyGuaranteeHoldsAfterRandomFaultsUnderConcurrentLoad() {
        long seed = ChaosSeed.next("everyGuaranteeHoldsAfterRandomFaultsUnderConcurrentLoad");
        List<String> orgs = List.of(
                newOrg(ORG_PREFIX),
                newOrg(ORG_PREFIX),
                newOrg(ORG_PREFIX),
                newOrg(ORG_PREFIX),
                newOrg(ORG_PREFIX),
                newOrg(ORG_PREFIX),
                newOrg(ORG_PREFIX),
                newOrg(ORG_PREFIX));
        ChaosMonkey monkey = new ChaosMonkey(seed, broker, postgres, jdbc, ORG_PREFIX, orgs);

        try (LoadGenerator load =
                new LoadGenerator(transaction, writer, ledger, orgs, WRITERS, 0.1, Duration.ofMillis(30), seed)) {
            monkey.start();
            Pauses.sleep(Duration.ofSeconds(SOAK_SECONDS));
            monkey.stop();
        } finally {
            monkey.stop();
        }

        awaitDrained(orgs);
        System.out.printf("[chaos] soak committed=%d strikes=%s%n", ledger.totalCommitted(), monkey.landed());
        assertThat(monkey.landed().keySet())
                .as("every kind of fault landed")
                .containsExactlyInAnyOrder(Strike.values());
        assertThat(ledger.totalCommitted()).as("the load actually ran").isGreaterThan(200);
        Deliveries delivered = deliveries();
        OutboxInvariants.verifyAll(
                ledger,
                delivered,
                jdbc,
                consumer,
                monkey.maxRedeliveries(
                        properties.outbox().pollInterval(), properties.outbox().batchSize()));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.outbox_events WHERE status = 'PARKED' AND org_id LIKE ?",
                        Integer.class,
                        ORG_PREFIX + "-%"))
                .as("only a poison row may park, and the monkey removes it")
                .isZero();
    }
}
