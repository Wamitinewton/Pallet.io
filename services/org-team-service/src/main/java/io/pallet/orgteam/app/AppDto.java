package io.pallet.orgteam.app;

import java.time.Instant;
import java.util.UUID;

public record AppDto(
        UUID id,
        String name,
        String slug,
        CloudProvider cloudProvider,
        String region,
        UUID teamId,
        Instant createdAt,
        Instant updatedAt) {

    static AppDto of(App app) {
        return new AppDto(
                app.getId(),
                app.getName(),
                app.getSlug(),
                app.getCloudProvider(),
                app.getRegion(),
                app.getTeamId(),
                app.getCreatedAt(),
                app.getUpdatedAt());
    }
}
