package io.pallet.orgteam.app;

import io.pallet.orgteam.config.OrgTeamProperties;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RegionCatalog {

    private final Map<CloudProvider, Set<String>> regions = new EnumMap<>(CloudProvider.class);

    RegionCatalog(OrgTeamProperties properties) {
        OrgTeamProperties.Apps.Regions configured = properties.apps().regions();
        register(CloudProvider.AWS, configured.aws());
        register(CloudProvider.GCP, configured.gcp());
    }

    public boolean isValid(CloudProvider provider, String region) {
        return provider != null && region != null && regions.get(provider).contains(region);
    }

    private void register(CloudProvider provider, List<String> configured) {
        if (configured == null || configured.isEmpty()) {
            throw new IllegalStateException("No regions configured for " + provider);
        }
        if (configured.stream().anyMatch(region -> region == null || region.isBlank())) {
            throw new IllegalStateException("Blank region configured for " + provider);
        }
        regions.put(provider, Set.copyOf(configured));
    }
}
