package io.pallet.identity.verification;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, UUID> {

    Optional<EmailVerificationCode> findByUserIdAndConsumedAtIsNull(UUID userId);

    void deleteByUserIdAndConsumedAtIsNull(UUID userId);
}
