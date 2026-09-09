package io.pallet.notification.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.pallet.common.messaging.EventIdempotencyGuard;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.notification.domain.Audience;
import io.pallet.notification.domain.Notification;
import io.pallet.notification.repository.NotificationRepository;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class NotificationEventIdempotencyGuardIntegrationTest {

    @Autowired
    private EventIdempotencyGuard guard;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    void markProcessedOnAFreshEventIdReturnsTrue() {
        assertThat(guard.markProcessed(UUID.randomUUID().toString(), Duration.ofMinutes(5)))
                .isTrue();
    }

    @Test
    void markProcessedReturnsFalseOnceANotificationForThatEventIdExists() {
        UUID sourceEventId = UUID.randomUUID();
        notificationRepository.saveAndFlush(new Notification(
                "org-1", "welcome-email", sourceEventId, null, Audience.SINGLE, "Welcome!", "<p>Hi</p>", Map.of()));

        assertThat(guard.markProcessed(sourceEventId.toString(), Duration.ofMinutes(5)))
                .isFalse();
    }

    @Test
    void releaseDoesNotThrowAndDoesNotChangeRepositoryState() {
        String eventId = UUID.randomUUID().toString();
        long countBefore = notificationRepository.count();

        assertThatCode(() -> guard.release(eventId)).doesNotThrowAnyException();

        assertThat(notificationRepository.count()).isEqualTo(countBefore);
    }
}
