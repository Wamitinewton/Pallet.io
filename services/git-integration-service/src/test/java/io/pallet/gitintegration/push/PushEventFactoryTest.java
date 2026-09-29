package io.pallet.gitintegration.push;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.events.GitPushReceived;
import io.pallet.common.test.annotations.UnitTest;
import io.pallet.gitintegration.delivery.payload.PushPayload;
import io.pallet.gitintegration.delivery.payload.PushPayload.HeadCommit;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@UnitTest
class PushEventFactoryTest {

    private static final Instant NOW = Instant.parse("2026-09-29T10:15:30Z");
    private static final String BEFORE = "1".repeat(40);
    private static final String AFTER = "2".repeat(40);

    private final PushEventFactory factory = new PushEventFactory(Clock.fixed(NOW, ZoneOffset.UTC));

    private record Link(
            UUID appId,
            String orgId,
            long installationId,
            long repoId,
            String repoFullName,
            String productionBranch,
            String rootDirectory)
            implements PushTarget {}

    private static Link link(String orgId, String rootDirectory) {
        return new Link(UUID.randomUUID(), orgId, 77, 42, "octo-org/api", "main", rootDirectory);
    }

    private static PushPayload push() {
        return new PushPayload(
                "refs/heads/main",
                BEFORE,
                AFTER,
                false,
                false,
                true,
                77,
                42,
                "octo-org/api-renamed",
                9,
                Optional.of(new HeadCommit(AFTER, "Fix the build", "octocat", false)),
                List.of());
    }

    @Test
    void theSameDeliveryAndAppAlwaysGiveTheSameId() {
        Link link = link("org-a", null);
        UUID delivery = UUID.randomUUID();

        UUID first = factory.fromWebhook(link, push(), delivery).eventId();
        UUID retried = factory.fromWebhook(link, push(), delivery).eventId();

        assertThat(retried).isEqualTo(first);
        assertThat(first.version()).isEqualTo(5);
        assertThat(first.variant()).isEqualTo(2);
    }

    @Test
    void differentAppsOrDeliveriesGiveDifferentIds() {
        UUID delivery = UUID.randomUUID();
        Link one = link("org-a", null);
        Link other = link("org-a", null);

        assertThat(factory.fromWebhook(one, push(), delivery).eventId())
                .isNotEqualTo(factory.fromWebhook(other, push(), delivery).eventId())
                .isNotEqualTo(
                        factory.fromWebhook(one, push(), UUID.randomUUID()).eventId());
    }

    @Test
    void theIdIsTheRfcNameBasedUuidOfTriggerAndApp() {
        UUID dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

        assertThat(PushEventFactory.nameBased(dns, "www.example.com"))
                .isEqualTo(UUID.fromString("2ed6657d-e927-568b-95e1-2665a8aea6a2"));
    }

    @Test
    void eachOrgGetsItsOwnEventOnItsOwnKey() {
        UUID delivery = UUID.randomUUID();

        GitPushReceived forA = factory.fromWebhook(link("org-a", null), push(), delivery);
        GitPushReceived forB = factory.fromWebhook(link("org-b", null), push(), delivery);

        assertThat(forA.orgId()).isEqualTo("org-a");
        assertThat(forB.orgId()).isEqualTo("org-b");
    }

    @Test
    void aWebhookPushCarriesThePushAndTheDelivery() {
        Link link = link("org-a", "apps/web");
        UUID delivery = UUID.randomUUID();

        GitPushReceived event = factory.fromWebhook(link, push(), delivery);

        assertThat(event.eventType()).isEqualTo(GitPushReceived.TYPE);
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.repo()).isEqualTo("octo-org/api-renamed");
        assertThat(event.branch()).isEqualTo("main");
        assertThat(event.commitSha()).isEqualTo(AFTER);
        assertThat(event.beforeSha()).isEqualTo(BEFORE);
        assertThat(event.forced()).isTrue();
        assertThat(event.appId()).isEqualTo(link.appId().toString());
        assertThat(event.provider()).isEqualTo(GitPushReceived.PROVIDER_GITHUB);
        assertThat(event.installationId()).isEqualTo(77);
        assertThat(event.repoId()).isEqualTo(42);
        assertThat(event.rootDirectory()).isEqualTo("apps/web");
        assertThat(event.trigger()).isEqualTo(GitPushReceived.TRIGGER_WEBHOOK);
        assertThat(event.headCommitMessage()).isEqualTo("Fix the build");
        assertThat(event.headCommitAuthor()).isEqualTo("octocat");
        assertThat(event.deliveryId()).isEqualTo(delivery.toString());
    }

    @Test
    void aManualBuildIsTheRequestedCommitWithoutADelivery() {
        Link link = link("org-a", null);
        String before = "c".repeat(40);

        GitPushReceived event = factory.manual(link, "release", before, AFTER, "key-1");

        assertThat(event.trigger()).isEqualTo(GitPushReceived.TRIGGER_MANUAL);
        assertThat(event.branch()).isEqualTo("release");
        assertThat(event.commitSha()).isEqualTo(AFTER);
        assertThat(event.repo()).isEqualTo("octo-org/api");
        assertThat(event.beforeSha()).isEqualTo(before);
        assertThat(event.forced()).isFalse();
        assertThat(event.deliveryId()).isNull();
        assertThat(event.eventId())
                .isEqualTo(
                        factory.manual(link, "release", before, AFTER, "key-1").eventId())
                .isNotEqualTo(
                        factory.manual(link, "release", before, AFTER, "key-2").eventId());
    }

    @Test
    void aLinkedPushBuildsTheProductionBranchHeadUnderAFreshId() {
        Link link = link("org-a", "apps/web");

        GitPushReceived event = factory.linked(link, AFTER);

        assertThat(event.trigger()).isEqualTo(GitPushReceived.TRIGGER_LINKED);
        assertThat(event.branch()).isEqualTo("main");
        assertThat(event.commitSha()).isEqualTo(AFTER);
        assertThat(event.rootDirectory()).isEqualTo("apps/web");
        assertThat(event.deliveryId()).isNull();
        assertThat(event.eventId()).isNotEqualTo(factory.linked(link, AFTER).eventId());
    }

    @Test
    void aReconciledPushCarriesTheHeadItMovedFrom() {
        Link link = link("org-a", null);
        UUID run = UUID.randomUUID();

        GitPushReceived event = factory.reconciled(link, BEFORE, AFTER, run);

        assertThat(event.trigger()).isEqualTo(GitPushReceived.TRIGGER_RECONCILED);
        assertThat(event.branch()).isEqualTo("main");
        assertThat(event.beforeSha()).isEqualTo(BEFORE);
        assertThat(event.commitSha()).isEqualTo(AFTER);
        assertThat(event.deliveryId()).isNull();
        assertThat(event.eventId())
                .isEqualTo(factory.reconciled(link, BEFORE, AFTER, run).eventId());
    }
}
