package io.pallet.notification.repository;

import io.pallet.notification.domain.Notification;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Optional<Notification> findBySourceEventId(UUID sourceEventId);
}
