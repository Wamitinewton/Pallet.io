package io.pallet.orgteam.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.dockerjava.api.DockerClient;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.outbox.TopicProbe.Received;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class OutboxRelayFailureIntegrationTest extends OutboxIntegrationSupport {

    private static final Duration QUIET = Duration.ofSeconds(1);

    @Test
    void aCrashAfterTheBrokerAckedRepublishesTheSameEventId() {
        String orgId = newOrgId();
        OrgMemberAdded event = memberAdded(orgId);
        commit(event);
        OutboxRepository crashingBeforeCommit = new OutboxRepository(jdbcClient) {
            @Override
            void markPublished(long id) {
                throw new IllegalStateException("simulated crash before commit");
            }
        };

        assertThatThrownBy(() -> newRelay(crashingBeforeCommit, java.time.Clock.systemUTC())
                        .tick())
                .isInstanceOf(IllegalStateException.class);
        assertThat(statusOf(event.eventId())).isEqualTo("PENDING");

        newRelay().tick();

        assertThat(statusOf(event.eventId())).isEqualTo("PUBLISHED");
        assertThat(attemptsOf(event.eventId())).isZero();
        try (TopicProbe probe = probe(OrgMemberAdded.TYPE)) {
            assertThat(eventIds(probe.awaitCount(orgId, 2))).containsExactly(event.eventId(), event.eventId());
        }
    }

    @Test
    void aBrokerOutageParksNothingBacksOffAndDrainsInOrderAfterwards() throws Exception {
        String orgId = newOrgId();
        OrgMemberAdded first = memberAdded(orgId);
        OrgMemberAdded second = memberAdded(orgId);
        OrgMemberAdded third = memberAdded(orgId);
        MutableClock clock = new MutableClock();
        OutboxRelay relay = newRelay(repository, clock);
        commit(first, second, third);

        DockerClient docker = kafka.getDockerClient();
        docker.pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            for (Duration expected : List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4))) {
                relay.tick();
                assertThat(relay.brokerBackoff()).isEqualTo(expected);
                clock.advance(expected);
            }
            for (OrgMemberAdded event : List.of(first, second, third)) {
                assertThat(statusOf(event.eventId())).isEqualTo("PENDING");
                assertThat(attemptsOf(event.eventId())).isZero();
            }
        } finally {
            docker.unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        try (TopicProbe probe = probe(OrgMemberAdded.TYPE)) {
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (pendingCount() > 0 && System.nanoTime() < deadline) {
                clock.advance(Duration.ofSeconds(30));
                relay.tick();
            }
            assertThat(pendingCount()).isZero();
            assertThat(relay.brokerBackoff()).isEqualTo(Duration.ZERO);
            assertThat(eventIds(probe.awaitCount(orgId, 3)).stream().distinct())
                    .containsExactly(first.eventId(), second.eventId(), third.eventId());
        }
        assertThat(jdbc.queryForObject(
                        "select count(*) from org_team.outbox_events where status = 'PARKED'", Integer.class))
                .isZero();
    }

    @Test
    void aPoisonRowHoldsOnlyItsOwnOrgAndParksAfterMaxAttempts() {
        String poisoned = newOrgId();
        String healthy = newOrgId();
        UUID poisonEventId = UUID.randomUUID();
        jdbc.update(
                "insert into org_team.outbox_events (event_id, org_id, event_type, payload) values (?, ?, 'bogus.event', '{}'::jsonb)",
                poisonEventId,
                poisoned);
        OrgMemberAdded otherOrgEvent = memberAdded(healthy);
        OrgMemberAdded laterSameOrgEvent = memberAdded(poisoned);
        commit(otherOrgEvent, laterSameOrgEvent);
        OutboxRelay relay = newRelay();

        relay.tick();

        assertThat(statusOf(otherOrgEvent.eventId())).isEqualTo("PUBLISHED");
        assertThat(attemptsOf(poisonEventId)).isEqualTo(1);
        assertThat(statusOf(poisonEventId)).isEqualTo("PENDING");
        assertThat(statusOf(laterSameOrgEvent.eventId())).isEqualTo("PENDING");
        double firstDelay = secondsUntilNextAttempt(poisonEventId);

        makeEligibleNow(poisonEventId);
        relay.tick();
        assertThat(attemptsOf(poisonEventId)).isEqualTo(2);
        assertThat(secondsUntilNextAttempt(poisonEventId)).isGreaterThan(firstDelay);

        makeEligibleNow(poisonEventId);
        relay.tick();
        assertThat(statusOf(poisonEventId)).isEqualTo("PARKED");
        assertThat(jdbc.queryForObject(
                        "select last_error from org_team.outbox_events where event_id = ?",
                        String.class,
                        poisonEventId))
                .contains("bogus.event");

        relay.tick();
        assertThat(statusOf(laterSameOrgEvent.eventId())).isEqualTo("PENDING");

        jdbc.update("delete from org_team.outbox_events where event_id = ?", poisonEventId);
        relay.tick();
        assertThat(statusOf(laterSameOrgEvent.eventId())).isEqualTo("PUBLISHED");
    }

    @Test
    void aRowWaitingOutItsBackoffBlocksItsOwnOrgButNotOthers() {
        String waiting = newOrgId();
        String other = newOrgId();
        OrgMemberAdded delayed = memberAdded(waiting);
        OrgMemberAdded behindDelayed = memberAdded(waiting);
        OrgMemberAdded unrelated = memberAdded(other);
        commit(delayed, behindDelayed, unrelated);
        jdbc.update(
                "update org_team.outbox_events set next_attempt_at = now() + interval '1 hour' where event_id = ?",
                delayed.eventId());
        OutboxRelay relay = newRelay();

        relay.tick();

        assertThat(statusOf(unrelated.eventId())).isEqualTo("PUBLISHED");
        assertThat(statusOf(delayed.eventId())).isEqualTo("PENDING");
        assertThat(attemptsOf(delayed.eventId())).isZero();
        assertThat(statusOf(behindDelayed.eventId())).isEqualTo("PENDING");

        makeEligibleNow(delayed.eventId());
        relay.tick();

        try (TopicProbe probe = probe(OrgMemberAdded.TYPE)) {
            assertThat(eventIds(probe.awaitCount(waiting, 2)))
                    .containsExactly(delayed.eventId(), behindDelayed.eventId());
            assertThat(probe.observe(waiting, QUIET)).hasSize(2);
        }
    }

    private int pendingCount() {
        return jdbc.queryForObject(
                "select count(*) from org_team.outbox_events where status = 'PENDING'", Integer.class);
    }

    private double secondsUntilNextAttempt(UUID eventId) {
        return jdbc.queryForObject(
                "select extract(epoch from next_attempt_at - now()) from org_team.outbox_events where event_id = ?",
                Double.class,
                eventId);
    }

    private static List<UUID> eventIds(List<Received> received) {
        return received.stream().map(Received::eventId).toList();
    }
}
