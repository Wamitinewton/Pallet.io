package io.pallet.orgteam.outbox.chaos;

import io.pallet.orgteam.outbox.TopicProbe;
import io.pallet.orgteam.outbox.TopicProbe.Received;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/** Everything a topic actually carried for the ledger's orgs, duplicates included, in arrival order. */
record Deliveries(Map<String, List<Received>> byOrg) {

    private static final Duration SETTLE = Duration.ofSeconds(1);

    static Deliveries collect(KafkaContainer kafka, JsonMapper jsonMapper, DeliveryLedger ledger, String topic) {
        Map<String, List<Received>> byOrg = new LinkedHashMap<>();
        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), jsonMapper, topic)) {
            String anyOrg = null;
            for (String orgId : ledger.orgs()) {
                probe.awaitCount(orgId, ledger.committedFor(orgId).size());
                anyOrg = orgId;
            }
            if (anyOrg != null) {
                probe.observe(anyOrg, SETTLE);
            }
            for (String orgId : ledger.orgs()) {
                byOrg.put(orgId, new ArrayList<>(probe.observe(orgId, Duration.ZERO)));
            }
        }
        return new Deliveries(byOrg);
    }

    List<Received> forOrg(String orgId) {
        return byOrg.getOrDefault(orgId, List.of());
    }

    List<UUID> idsForOrg(String orgId) {
        return forOrg(orgId).stream().map(Received::eventId).toList();
    }

    /** The order in which each event id first appeared, which is the order a deduplicating consumer sees. */
    List<UUID> firstOccurrenceOrder(String orgId) {
        return List.copyOf(new LinkedHashSet<>(idsForOrg(orgId)));
    }

    int duplicateCount() {
        return byOrg.keySet().stream()
                .mapToInt(
                        org -> idsForOrg(org).size() - firstOccurrenceOrder(org).size())
                .sum();
    }

    List<Received> all() {
        return byOrg.values().stream().flatMap(List::stream).toList();
    }
}
