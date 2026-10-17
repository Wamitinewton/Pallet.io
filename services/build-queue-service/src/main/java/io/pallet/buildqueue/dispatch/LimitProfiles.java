package io.pallet.buildqueue.dispatch;

import io.pallet.buildqueue.config.BuildQueueProperties;
import io.pallet.buildqueue.config.BuildQueueProperties.Profile;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Resolves an org's limits on every call, never caching them on the org, so a configuration change applies to the next
 * request or dispatcher round. Startup validation guarantees every name it resolves exists.
 */
@Component
public class LimitProfiles {

    /** One {@code limits.profiles} entry, as the dispatcher's candidate query joins it. */
    public record ProfileRow(String profile, int maxConcurrent) {}

    /** One {@code limits.org-profiles} entry. */
    public record OverrideRow(String orgId, String profile) {}

    private final BuildQueueProperties properties;

    LimitProfiles(BuildQueueProperties properties) {
        this.properties = properties;
    }

    /** The {@code org-profiles} override for the org, else {@code default-profile}. */
    public Profile forOrg(String orgId) {
        return properties.limits().forOrg(orgId);
    }

    public String profileName(String orgId) {
        return properties.limits().profileName(orgId);
    }

    public String defaultProfileName() {
        return properties.limits().defaultProfile();
    }

    /** Every profile, ordered by name so the query text is the same from round to round. */
    public List<ProfileRow> asValuesRows() {
        return sorted(properties.limits().profiles()).entrySet().stream()
                .map(entry -> new ProfileRow(entry.getKey(), entry.getValue().maxConcurrent()))
                .toList();
    }

    /** Every org override, ordered by org id. */
    public List<OverrideRow> overrideRows() {
        return sorted(properties.limits().orgProfiles()).entrySet().stream()
                .map(entry -> new OverrideRow(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static <V> Map<String, V> sorted(Map<String, V> map) {
        return new TreeMap<>(map);
    }
}
