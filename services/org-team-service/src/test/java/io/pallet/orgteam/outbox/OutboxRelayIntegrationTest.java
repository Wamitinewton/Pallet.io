package io.pallet.orgteam.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.events.EventHeaders;
import io.pallet.common.events.NotificationRequested;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.outbox.TopicProbe.Received;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class OutboxRelayIntegrationTest extends OutboxIntegrationSupport {

    private static final Duration QUIET = Duration.ofSeconds(1);

    @Autowired
    private DataSource dataSource;

    @Test
    void appendOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> writer.append(memberAdded(newOrgId())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aCommittedEventIsPublishedAndARolledBackOneNeverExists() {
        String orgId = newOrgId();
        OrgMemberAdded committed = memberAdded(orgId);
        OrgMemberAdded rolledBack = memberAdded(orgId);
        commit(committed);
        transaction.executeWithoutResult(status -> {
            writer.append(rolledBack);
            status.setRollbackOnly();
        });

        newRelay().tick();

        try (TopicProbe probe = probe(OrgMemberAdded.TYPE)) {
            List<Received> received = probe.awaitCount(orgId, 1);
            assertThat(received).hasSize(1);
            Received message = received.get(0);
            assertThat(message.key()).isEqualTo(orgId);
            assertThat(message.eventId()).isEqualTo(committed.eventId());
            assertThat(message.headers()).containsEntry(EventHeaders.ORG_ID, orgId);
            assertThat(probe.observe(orgId, QUIET)).hasSize(1);
        }
        assertThat(statusOf(committed.eventId())).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject(
                        "select count(*) from org_team.outbox_events where event_id = ?",
                        Integer.class,
                        rolledBack.eventId()))
                .isZero();
    }

    @Test
    void eachOrgsEventsArePublishedInTheOrderTheyWereAppended() {
        String orgA = newOrgId();
        String orgB = newOrgId();
        OrgMemberAdded a1 = memberAdded(orgA);
        OrgMemberAdded b1 = memberAdded(orgB);
        OrgMemberAdded a2 = memberAdded(orgA);
        OrgMemberAdded b2 = memberAdded(orgB);
        OrgMemberAdded a3 = memberAdded(orgA);
        commit(a1);
        commit(b1);
        commit(a2);
        commit(b2);
        commit(a3);

        newRelay().tick();

        try (TopicProbe probe = probe(OrgMemberAdded.TYPE)) {
            assertThat(eventIds(probe.awaitCount(orgA, 3))).containsExactly(a1.eventId(), a2.eventId(), a3.eventId());
            assertThat(eventIds(probe.awaitCount(orgB, 2))).containsExactly(b1.eventId(), b2.eventId());
        }
    }

    @Test
    void aSensitivePayloadIsScrubbedFromPostgresButStillDelivered() {
        String orgId = newOrgId();
        NotificationRequested event = NotificationRequested.of(
                orgId,
                "ORG_INVITE",
                "invitee@example.com",
                "EMAIL",
                "invite:1:1",
                Map.of("acceptUrl", "https://x/tok"));
        transaction.executeWithoutResult(status -> writer.append(event, true));

        newRelay().tick();

        try (TopicProbe probe = probe(NotificationRequested.TYPE)) {
            List<Received> received = probe.awaitCount(orgId, 1);
            assertThat(received).hasSize(1);
            assertThat(received.get(0).body().get("variables").get("acceptUrl").asString())
                    .isEqualTo("https://x/tok");
        }
        assertThat(statusOf(event.eventId())).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject(
                        "select payload is null from org_team.outbox_events where event_id = ?",
                        Boolean.class,
                        event.eventId()))
                .isTrue();
    }

    @Test
    void aRelayHoldingNoLockPublishesNothingWhileAnotherSessionHoldsIt() throws Exception {
        String orgId = newOrgId();
        OrgMemberAdded event = memberAdded(orgId);
        commit(event);
        long key = properties.outbox().advisoryLockKey();

        try (TopicProbe probe = probe(OrgMemberAdded.TYPE);
                Connection holder = dataSource.getConnection()) {
            holder.createStatement().execute("select pg_advisory_lock(" + key + ")");
            newRelay().tick();
            assertThat(probe.observe(orgId, QUIET)).isEmpty();
            assertThat(statusOf(event.eventId())).isEqualTo("PENDING");

            holder.createStatement().execute("select pg_advisory_unlock(" + key + ")");
            newRelay().tick();
            assertThat(probe.awaitCount(orgId, 1)).hasSize(1);
            assertThat(statusOf(event.eventId())).isEqualTo("PUBLISHED");
        }
    }

    @Test
    void twoRelaysOnOneDatabasePublishEveryEventExactlyOnceAndInOrder() throws Exception {
        List<String> orgs = List.of(newOrgId(), newOrgId(), newOrgId(), newOrgId());
        Map<String, List<UUID>> expected = new HashMap<>();
        for (int round = 0; round < 10; round++) {
            for (String orgId : orgs) {
                OrgMemberAdded event = memberAdded(orgId);
                commit(event);
                expected.computeIfAbsent(orgId, k -> new ArrayList<>()).add(event.eventId());
            }
        }
        OutboxRelay first = newRelay();
        OutboxRelay second = newRelay();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> runs = new ArrayList<>();
            for (OutboxRelay relay : List.of(first, second)) {
                runs.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 30; i++) {
                        relay.tick();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> run : runs) {
                run.get();
            }
        } finally {
            executor.shutdownNow();
        }

        try (TopicProbe probe = probe(OrgMemberAdded.TYPE)) {
            for (String orgId : orgs) {
                probe.awaitCount(orgId, expected.get(orgId).size());
            }
            for (String orgId : orgs) {
                assertThat(eventIds(probe.observe(orgId, QUIET))).containsExactlyElementsOf(expected.get(orgId));
            }
        }
        assertThat(jdbc.queryForObject(
                        "select count(*) from org_team.outbox_events where status <> 'PUBLISHED'", Integer.class))
                .isZero();
    }

    private static List<UUID> eventIds(List<Received> received) {
        return received.stream().map(Received::eventId).toList();
    }
}
