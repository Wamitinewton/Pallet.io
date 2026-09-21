package io.pallet.orgteam.outbox.chaos;

import io.pallet.common.events.PlatformEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ground truth for a run: what the application was told committed, and what it was told rolled
 * back. Record only after the transaction outcome is known. Per-org order is meaningful only when a
 * single writer owns each org, which every test that asserts ordering guarantees.
 */
final class DeliveryLedger {

    private final Map<String, List<UUID>> committed = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> rolledBack = new ConcurrentHashMap<>();

    void committed(PlatformEvent event) {
        committed(event.orgId(), event.eventId());
    }

    void committed(String orgId, UUID eventId) {
        committed.computeIfAbsent(orgId, org -> new CopyOnWriteArrayList<>()).add(eventId);
    }

    void rolledBack(PlatformEvent event) {
        rolledBack
                .computeIfAbsent(event.orgId(), org -> ConcurrentHashMap.newKeySet())
                .add(event.eventId());
    }

    Set<String> orgs() {
        Set<String> orgs = ConcurrentHashMap.newKeySet();
        orgs.addAll(committed.keySet());
        orgs.addAll(rolledBack.keySet());
        return orgs;
    }

    List<UUID> committedFor(String orgId) {
        return new ArrayList<>(committed.getOrDefault(orgId, List.of()));
    }

    Set<UUID> rolledBackFor(String orgId) {
        return Collections.unmodifiableSet(rolledBack.getOrDefault(orgId, Set.of()));
    }

    int totalCommitted() {
        return committed.values().stream().mapToInt(List::size).sum();
    }
}
