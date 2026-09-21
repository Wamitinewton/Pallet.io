package io.pallet.notification.audience;

import io.pallet.common.events.OrgDeleted;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.events.OrgMemberRemoved;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrgMembershipProjectionService {

    private final OrgMembershipRepository repository;

    OrgMembershipProjectionService(OrgMembershipRepository repository) {
        this.repository = repository;
    }

    @Transactional
    void apply(OrgMemberAdded event) {
        requireText(event.orgId(), "orgId");
        requireText(event.userId(), "userId");
        requireText(event.email(), "email");
        requirePresent(event.occurredAt(), "occurredAt");
        repository.upsertActive(event.orgId(), event.userId(), event.email(), event.occurredAt());
    }

    @Transactional
    void apply(OrgMemberRemoved event) {
        requireText(event.orgId(), "orgId");
        requireText(event.userId(), "userId");
        requireText(event.email(), "email");
        requirePresent(event.occurredAt(), "occurredAt");
        repository.markRemoved(event.orgId(), event.userId(), event.email(), event.occurredAt());
    }

    @Transactional
    void apply(OrgDeleted event) {
        requireText(event.orgId(), "orgId");
        requirePresent(event.occurredAt(), "occurredAt");
        repository.markAllRemovedForOrg(event.orgId(), event.occurredAt());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Membership event is missing required field '%s'".formatted(field));
        }
    }

    private static void requirePresent(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Membership event is missing required field '%s'".formatted(field));
        }
    }
}
