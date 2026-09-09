package io.pallet.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.annotations.RepositoryTest;
import io.pallet.notification.domain.Audience;
import io.pallet.notification.domain.Notification;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@RepositoryTest
class NotificationRepositoryIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    private static Notification notification(String orgId, UUID sourceEventId, String dedupeKey) {
        return new Notification(
                orgId,
                "welcome-email",
                sourceEventId,
                dedupeKey,
                Audience.SINGLE,
                "Welcome!",
                "<p>Welcome aboard.</p>",
                Map.of("firstName", "Ada", "loginCount", 3));
    }

    @Test
    void savesAndRoundTripsEveryFieldIncludingVariablesAsJsonb() {
        Notification saved = notificationRepository.saveAndFlush(notification("org-1", UUID.randomUUID(), null));

        Optional<Notification> found = notificationRepository.findBySourceEventId(saved.getSourceEventId());

        assertThat(found).isPresent();
        Notification loaded = found.get();
        assertThat(loaded.getId()).isEqualTo(saved.getId());
        assertThat(loaded.getOrgId()).isEqualTo("org-1");
        assertThat(loaded.getNotificationType()).isEqualTo("welcome-email");
        assertThat(loaded.getSourceEventId()).isEqualTo(saved.getSourceEventId());
        assertThat(loaded.getDedupeKey()).isNull();
        assertThat(loaded.getAudience()).isEqualTo(Audience.SINGLE);
        assertThat(loaded.getRenderedTitle()).isEqualTo("Welcome!");
        assertThat(loaded.getRenderedBody()).isEqualTo("<p>Welcome aboard.</p>");
        assertThat(loaded.getVariables()).containsEntry("firstName", "Ada").containsEntry("loginCount", 3);
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void aSecondInsertWithTheSameSourceEventIdIsRejected() {
        UUID sourceEventId = UUID.randomUUID();
        notificationRepository.saveAndFlush(notification("org-1", sourceEventId, null));

        assertThatThrownBy(() -> notificationRepository.saveAndFlush(notification("org-2", sourceEventId, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSecondInsertWithTheSameOrgAndDedupeKeyIsRejected() {
        notificationRepository.saveAndFlush(notification("org-1", UUID.randomUUID(), "welcome-once"));

        assertThatThrownBy(() ->
                        notificationRepository.saveAndFlush(notification("org-1", UUID.randomUUID(), "welcome-once")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSameDedupeKeyUnderDifferentOrgsIsAllowed() {
        notificationRepository.saveAndFlush(notification("org-1", UUID.randomUUID(), "welcome-once"));

        Notification otherOrg =
                notificationRepository.saveAndFlush(notification("org-2", UUID.randomUUID(), "welcome-once"));

        assertThat(otherOrg.getId()).isNotNull();
    }

    @Test
    void nullDedupeKeysDoNotCollideWithEachOtherInTheSameOrg() {
        Notification first = notificationRepository.saveAndFlush(notification("org-1", UUID.randomUUID(), null));
        Notification second = notificationRepository.saveAndFlush(notification("org-1", UUID.randomUUID(), null));

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }
}
