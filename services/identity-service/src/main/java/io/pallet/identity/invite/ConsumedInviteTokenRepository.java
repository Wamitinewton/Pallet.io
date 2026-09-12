package io.pallet.identity.invite;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsumedInviteTokenRepository extends JpaRepository<ConsumedInviteToken, String> {}
