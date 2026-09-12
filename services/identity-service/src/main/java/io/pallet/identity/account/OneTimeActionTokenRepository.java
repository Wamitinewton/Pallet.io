package io.pallet.identity.account;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OneTimeActionTokenRepository extends JpaRepository<OneTimeActionToken, UUID> {

    Optional<OneTimeActionToken> findByTokenHashAndPurpose(String tokenHash, TokenPurpose purpose);
}
