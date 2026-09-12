package io.pallet.identity.signup;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrgBootstrapRepository extends JpaRepository<OrgBootstrapRecord, String> {

    boolean existsBySlug(String slug);
}
