package io.pallet.buildqueue.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.buildqueue.projection.AppProjection.Status;
import io.pallet.buildqueue.support.TopicProbe;
import io.pallet.common.events.AppCreated;
import io.pallet.common.events.AppDeleted;
import io.pallet.common.events.Topics;
import io.pallet.common.inbox.TransactionalInbox;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
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
    private OrgProjectionRepository orgs;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private KafkaContainer kafka;

    @Test
    void aCreatedAppIsActiveInItsOrgAndTheOrgIsKnown() {
        String orgId = newOrg();
        String appId = newApp();

        publisher.publish(created(orgId, appId));

        AppProjection app = awaitStatus(orgId, appId, Status.ACTIVE);
        assertThat(app.slug()).isEqualTo(slugOf(appId));
        assertThat(app.deletedAt()).isNull();
        assertThat(orgs.findByOrgId(orgId))
                .get()
                .extracting(OrgProjection::status)
                .isEqualTo(OrgProjection.Status.ACTIVE);
    }

    @Test
    void aDeletedAppIsMarkedDeletedWithItsDeletionTime() {
        String orgId = newOrg();
        String appId = newApp();
        publisher.publish(created(orgId, appId));
        awaitStatus(orgId, appId, Status.ACTIVE);

        publisher.publish(deleted(orgId, appId));

        assertThat(awaitStatus(orgId, appId, Status.DELETED).deletedAt()).isNotNull();
    }

    @Test
    void aDeletedArrivingFirstRecordsTheAppAndOrgAndALateCreatedCannotReviveIt() {
        String orgId = newOrg();
        String appId = newApp();
        publisher.publish(deleted(orgId, appId));
        AppProjection tombstone = awaitStatus(orgId, appId, Status.DELETED);
        assertThat(orgs.findByOrgId(orgId)).isPresent();

        AppCreated late = created(orgId, appId);
        publisher.publish(late);

        awaitClaimed(AppEventListener.CREATED_CONSUMER, late.eventId());
        AppProjection after = apps.findByOrgIdAndAppId(orgId, appId).orElseThrow();
        assertThat(after.status()).isEqualTo(Status.DELETED);
        assertThat(after.updatedAt()).isEqualTo(tombstone.updatedAt());
    }

    @Test
    void theSameCreatedTwiceLeavesOneRowAndOneClaim() {
        String orgId = newOrg();
        String appId = newApp();
        AppCreated event = created(orgId, appId);
        publisher.publish(event);
        AppProjection first = awaitStatus(orgId, appId, Status.ACTIVE);
        double duplicatesBefore = duplicates(AppEventListener.CREATED_CONSUMER);

        publisher.publish(event);

        await().atMost(WAIT).until(() -> duplicates(AppEventListener.CREATED_CONSUMER) > duplicatesBefore);
        assertThat(claims(AppEventListener.CREATED_CONSUMER, event.eventId())).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM build_queue.apps WHERE app_id = ?", Integer.class, appId))
                .isOne();
        assertThat(apps.findByOrgIdAndAppId(orgId, appId).orElseThrow().updatedAt())
                .isEqualTo(first.updatedAt());
    }

    @Test
    void anAppIdAnnouncedUnderASecondOrgIsDeadLetteredAndTheOwnerKeepsIt() {
        String owner = newOrg();
        String intruder = newOrg();
        String appId = newApp();
        publisher.publish(created(owner, appId));
        AppProjection owned = awaitStatus(owner, appId, Status.ACTIVE);

        AppDeleted hostileDelete = deleted(intruder, appId);
        AppCreated hostileCreate = created(intruder, appId);
        try (TopicProbe deletes = new TopicProbe(kafka.getBootstrapServers(), Topics.deadLetter(Topics.APP_DELETED));
                TopicProbe creates =
                        new TopicProbe(kafka.getBootstrapServers(), Topics.deadLetter(Topics.APP_CREATED))) {
            publisher.publish(hostileDelete);
            publisher.publish(hostileCreate);

            assertThat(deletes.awaitKey(intruder, 1)).hasSize(1);
            assertThat(creates.awaitKey(intruder, 1)).hasSize(1);
        }
        AppProjection after = apps.findByOrgIdAndAppId(owner, appId).orElseThrow();
        assertThat(after.status()).isEqualTo(Status.ACTIVE);
        assertThat(after.updatedAt()).isEqualTo(owned.updatedAt());
        assertThat(apps.findByOrgIdAndAppId(intruder, appId)).isEmpty();
        assertThat(orgs.findByOrgId(intruder)).isEmpty();
        assertThat(claims(AppEventListener.DELETED_CONSUMER, hostileDelete.eventId()))
                .isZero();
        assertThat(claims(AppEventListener.CREATED_CONSUMER, hostileCreate.eventId()))
                .isZero();
    }

    @Test
    void aMalformedPayloadIsDeadLetteredWithNothingStored() {
        String orgId = newOrg();
        AppCreated blankApp = new AppCreated(
                UUID.randomUUID(),
                AppCreated.TYPE,
                orgId,
                Instant.now(),
                " ",
                "Web",
                "web",
                null,
                "AWS",
                "us-east-1",
                "user-1");

        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), Topics.deadLetter(Topics.APP_CREATED))) {
            publisher.publish(blankApp);

            assertThat(probe.awaitKey(orgId, 1)).hasSize(1);
        }
        assertThat(claims(AppEventListener.CREATED_CONSUMER, blankApp.eventId()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM build_queue.apps WHERE org_id = ?", Integer.class, orgId))
                .isZero();
        assertThat(orgs.findByOrgId(orgId)).isEmpty();
    }

    private AppProjection awaitStatus(String orgId, String appId, Status status) {
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
                "SELECT count(*) FROM build_queue.processed_events WHERE consumer = ? AND event_id = ?",
                Integer.class,
                consumer,
                eventId);
    }

    private double duplicates(String consumer) {
        Counter counter = meterRegistry
                .find("build_queue." + TransactionalInbox.DUPLICATES)
                .tag(TransactionalInbox.TAG_LISTENER, consumer)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private static AppCreated created(String orgId, String appId) {
        return AppCreated.of(orgId, appId, "Web", slugOf(appId), null, "AWS", "us-east-1", "user-1");
    }

    private static AppDeleted deleted(String orgId, String appId) {
        return AppDeleted.of(orgId, appId, slugOf(appId), "user-1");
    }

    private static String slugOf(String appId) {
        return "app-" + appId.substring(0, 8);
    }

    private static String newApp() {
        return UUID.randomUUID().toString();
    }

    private static String newOrg() {
        return "org-" + UUID.randomUUID();
    }
}
