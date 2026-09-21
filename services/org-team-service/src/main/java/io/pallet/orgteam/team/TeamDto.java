package io.pallet.orgteam.team;

import java.time.Instant;
import java.util.UUID;

public record TeamDto(UUID id, String name, String slug, long memberCount, Instant createdAt, Instant updatedAt) {

    static TeamDto of(Team team, long memberCount) {
        return new TeamDto(
                team.getId(), team.getName(), team.getSlug(), memberCount, team.getCreatedAt(), team.getUpdatedAt());
    }
}
