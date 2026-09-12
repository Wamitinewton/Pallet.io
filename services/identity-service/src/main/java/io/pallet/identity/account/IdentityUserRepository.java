package io.pallet.identity.account;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentityUserRepository extends JpaRepository<IdentityUser, UUID> {

    boolean existsByEmail(String email);
}
