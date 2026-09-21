package io.pallet.orgteam.outbox.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.pallet.common.events.OrgMemberAdded;
import io.pallet.orgteam.observability.MetricsCatalog;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** The broker is slow, gone, or flapping; the outbox must delay, never lose, reorder or park. */
class BrokerFaultChaosIntegrationTest extends OutboxChaosSupport {

    private static final int OUTAGE_CYCLES = 5;
    private static final int REDELIVERIES_PER_OUTAGE = 4;

    @Test
    void anOutageStallsPublishingWithoutFailingReadinessAndDrainsInOrderOnRecovery() throws Exception {
        String orgId = newOrg();
        OrgMemberAdded first = memberAdded(orgId);
        OrgMemberAdded second = memberAdded(orgId);
        OrgMemberAdded third = memberAdded(orgId);
        readinessIsUp();
        double brokerFailuresBefore = brokerFailures();
        double rowFailuresBefore = rowFailures();

        try (Fault ignored = track(broker.pause())) {
            commit(first, second, third);

            await().atMost(WAIT).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
                outboxMetrics.refresh();
                assertThat(gauge(MetricsCatalog.OUTBOX_OLDEST_PENDING_AGE)).isGreaterThan(2);
                assertThat(brokerFailures()).isGreaterThan(brokerFailuresBefore);
            });
            assertThat(gauge(MetricsCatalog.OUTBOX_PENDING)).isGreaterThanOrEqualTo(3);
            readinessIsUp();
            for (OrgMemberAdded event : List.of(first, second, third)) {
                assertThat(statusOf(event.eventId())).isEqualTo("PENDING");
                assertThat(attemptsOf(event.eventId()))
                        .as("an unreachable broker is not the row's fault")
                        .isZero();
            }
            assertThat(rowFailures()).isEqualTo(rowFailuresBefore);
        }

        awaitDrained(List.of(orgId));
        OutboxInvariants.verifyAll(ledger, deliveries(), jdbc, consumer, REDELIVERIES_PER_OUTAGE);
    }

    @Test
    void aBrokerThatRecoversWithinTheSendTimeoutOnlyDelaysDeliveryAndCostsNoFailure() {
        String orgId = newOrg();
        OrgMemberAdded event = memberAdded(orgId);
        double brokerFailuresBefore = brokerFailures();

        try (Fault ignored = track(broker.pause())) {
            commit(event);
            Pauses.sleep(Duration.ofMillis(800));
        }

        awaitPublished(event.eventId());
        assertThat(brokerFailures()).isEqualTo(brokerFailuresBefore);
        assertThat(attemptsOf(event.eventId())).isZero();
        Deliveries delivered = deliveries();
        assertThat(delivered.idsForOrg(orgId))
                .as("a slow ack is one delivery, not a retry")
                .containsExactly(event.eventId());
    }

    @Test
    void aFlappingBrokerNeverLosesReordersOrParksAnything() {
        long seed = ChaosSeed.next("aFlappingBrokerNeverLosesReordersOrParksAnything");
        Random random = new Random(seed);
        List<String> orgs = newOrgs(3);

        try (LoadGenerator load =
                new LoadGenerator(transaction, writer, ledger, orgs, 3, 0.1, Duration.ofMillis(40), seed)) {
            for (int cycle = 0; cycle < OUTAGE_CYCLES; cycle++) {
                broker.outage(Duration.ofMillis(1200 + random.nextInt(1300)));
                Pauses.sleep(Duration.ofMillis(600 + random.nextInt(900)));
            }
        }

        awaitDrained(orgs);
        assertThat(ledger.totalCommitted()).as("the load actually ran").isGreaterThan(30);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.outbox_events WHERE status = 'PARKED' AND org_id IN (?, ?, ?)",
                        Integer.class,
                        orgs.toArray()))
                .as("broker faults must never park a row")
                .isZero();
        OutboxInvariants.verifyAll(ledger, deliveries(), jdbc, consumer, OUTAGE_CYCLES * REDELIVERIES_PER_OUTAGE);
    }

    private void readinessIsUp() throws Exception {
        MvcResult result = mvc.perform(get(READINESS)).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
    }
}
