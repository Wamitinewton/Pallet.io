package io.pallet.identity.account;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentityUserRepository extends JpaRepository<IdentityUser, UUID> {

    boolean existsByEmail(String email);

    Optional<IdentityUser> findByEmail(String email);

    Optional<IdentityUser> findByKeycloakUserId(String keycloakUserId);
}
