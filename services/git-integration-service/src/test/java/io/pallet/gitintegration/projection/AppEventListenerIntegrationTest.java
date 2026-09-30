package io.pallet.gitintegration.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.events.AppCreated;
import io.pallet.common.events.AppDeleted;
import io.pallet.common.events.Topics;
import io.pallet.common.inbox.TransactionalInbox;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.observability.MetricsCatalog;
import io.pallet.gitintegration.projection.AppProjection.Status;
import io.pallet.gitintegration.support.TopicProbe;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class AppEventListenerIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private AppProjectionRepository apps;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private KafkaContainer kafka;

    @Test
    void aCreatedAppIsActiveInItsOrg() {
        String orgId = newOrg();
        UUID appId = UUID.randomUUID();

        publisher.publish(created(orgId, appId));

        AppProjection app = awaitStatus(orgId, appId, Status.ACTIVE);
        assertThat(app.orgId()).isEqualTo(orgId);
        assertThat(app.slug()).isEqualTo(slugOf(appId));
    }

    @Test
    void aDeletedAppIsMarkedDeleted() {
        String orgId = newOrg();
        UUID appId = UUID.randomUUID();
        publisher.publish(created(orgId, appId));
        awaitStatus(orgId, appId, Status.ACTIVE);

        publisher.publish(deleted(orgId, appId));

        awaitStatus(orgId, appId, Status.DELETED);
    }

    @Test
    void aCreatedArrivingAfterItsDeletedCannotResurrectTheApp() {
        String orgId = newOrg();
        UUID appId = UUID.randomUUID();
        publisher.publish(deleted(orgId, appId));
        awaitStatus(orgId, appId, Status.DELETED);

        AppCreated late = created(orgId, appId);
        publisher.publish(late);

        awaitClaimed(AppEventListener.CREATED_CONSUMER, late.eventId());
        assertThat(apps.findByOrgIdAndAppId(orgId, appId))
                .get()
                .extracting(AppProjection::status)
                .isEqualTo(Status.DELETED);
    }

    @Test
    void aRedeliveredEventIsANoOp() {
        String orgId = newOrg();
        UUID appId = UUID.randomUUID();
        AppDeleted event = deleted(orgId, appId);
        publisher.publish(event);
        Instant firstApplied = awaitStatus(orgId, appId, Status.DELETED).updatedAt();
        double duplicatesBefore = duplicates(AppEventListener.DELETED_CONSUMER);

        publisher.publish(event);

        await().atMost(WAIT).until(() -> duplicates(AppEventListener.DELETED_CONSUMER) > duplicatesBefore);
        assertThat(apps.findByOrgIdAndAppId(orgId, appId).orElseThrow().updatedAt())
                .isEqualTo(firstApplied);
    }

    @Test
    void aMalformedPayloadIsDeadLetteredOnTheFirstFailureWithNothingStored() {
        String orgId = newOrg();
        AppCreated malformed = new AppCreated(
                UUID.randomUUID(),
                AppCreated.TYPE,
                orgId,
                Instant.now(),
                "not-a-uuid",
                "web",
                "web",
                null,
                "AWS",
                "us-east-1",
                "user-1");
        double failuresBefore = failures(AppEventListener.CREATED_CONSUMER);

        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), Topics.deadLetter(Topics.APP_CREATED))) {
            publisher.publish(malformed);

            assertThat(probe.awaitKey(orgId, 1)).hasSize(1);
        }
        assertThat(failures(AppEventListener.CREATED_CONSUMER) - failuresBefore)
                .as("dead-lettered without a retry")
                .isEqualTo(1.0);
        assertThat(claims(AppEventListener.CREATED_CONSUMER, malformed.eventId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM git_integration.apps WHERE org_id = ?", Integer.class, orgId))
                .isZero();
    }

    @Test
    void anAppIdAlreadyOwnedByAnotherOrgIsDeadLetteredAndTheOwnerKeepsIt() {
        String owner = newOrg();
        String intruder = newOrg();
        UUID appId = UUID.randomUUID();
        publisher.publish(created(owner, appId));
        awaitStatus(owner, appId, Status.ACTIVE);

        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), Topics.deadLetter(Topics.APP_DELETED))) {
            publisher.publish(deleted(intruder, appId));

            assertThat(probe.awaitKey(intruder, 1)).hasSize(1);
        }
        assertThat(apps.findByOrgIdAndAppId(owner, appId))
                .get()
                .extracting(AppProjection::status)
                .isEqualTo(Status.ACTIVE);
        assertThat(apps.findByOrgIdAndAppId(intruder, appId)).isEmpty();
    }

    private AppProjection awaitStatus(String orgId, UUID appId, Status status) {
        return await().atMost(WAIT)
                .until(
                        () -> apps.findByOrgIdAndAppId(orgId, appId),
                        found -> found.map(AppProjection::status).equals(Optional.of(status)))
                .orElseThrow();
    }

    private void awaitClaimed(String consumer, UUID eventId) {
        await().atMost(WAIT).until(() -> claims(consumer, eventId) == 1);
    }

    private int claims(String consumer, UUID eventId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM git_integration.processed_events WHERE consumer = ? AND event_id = ?",
                Integer.class,
                consumer,
                eventId);
    }

    private double duplicates(String consumer) {
        Counter counter = meterRegistry
                .find("git." + TransactionalInbox.DUPLICATES)
                .tag(TransactionalInbox.TAG_LISTENER, consumer)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private double failures(String consumer) {
        Counter counter = meterRegistry
                .find(MetricsCatalog.EVENTS_FAILED)
                .tag(MetricsCatalog.TAG_LISTENER, consumer)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private static AppCreated created(String orgId, UUID appId) {
        return AppCreated.of(orgId, appId.toString(), "Web", slugOf(appId), null, "AWS", "us-east-1", "user-1");
    }

    private static AppDeleted deleted(String orgId, UUID appId) {
        return AppDeleted.of(orgId, appId.toString(), slugOf(appId), "user-1");
    }

    private static String slugOf(UUID appId) {
        return "app-" + appId.toString().substring(0, 8);
    }

    private static String newOrg() {
        return "org-" + UUID.randomUUID();
    }
}
