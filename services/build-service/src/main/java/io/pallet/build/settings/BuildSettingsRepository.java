package io.pallet.build.settings;

import io.pallet.build.domain.SettingsSnapshot;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface BuildSettingsRepository extends Repository<BuildSettings, String> {

    Optional<BuildSettings> findByOrgIdAndAppId(String orgId, String appId);

    default SettingsSnapshot snapshotOf(String orgId, String appId) {
        return findByOrgIdAndAppId(orgId, appId).map(BuildSettings::snapshot).orElse(SettingsSnapshot.DEFAULTS);
    }
}
