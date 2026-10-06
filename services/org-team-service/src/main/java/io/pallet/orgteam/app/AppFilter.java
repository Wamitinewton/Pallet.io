package io.pallet.orgteam.app;

import io.pallet.common.error.BadRequestException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

public record AppFilter(
        @Schema(description = "Only apps owned by this team")
        UUID teamId,

        @Schema(description = "Only apps with no owning team; cannot be combined with teamId")
        Boolean unassigned,

        @Schema(description = "Only apps running on this cloud provider")
        CloudProvider cloudProvider,

        @Schema(description = "Case-insensitive match anywhere in the app's name or slug", maxLength = 100)
        @Size(max = 100) String q) {

    public AppFilter {
        q = q == null || q.isBlank() ? null : q.strip();
    }

    Specification<App> toSpecification(String orgId) {
        boolean withoutTeam = Boolean.TRUE.equals(unassigned);
        if (withoutTeam && teamId != null) {
            throw new BadRequestException(
                    "Filter by a team or by apps without a team, not both.", "teamId combined with unassigned=true");
        }

        List<Specification<App>> clauses = new ArrayList<>();
        clauses.add((app, query, cb) -> cb.equal(app.get("orgId"), orgId));
        clauses.add((app, query, cb) -> cb.equal(app.get("status"), AppStatus.ACTIVE));
        if (teamId != null) {
            clauses.add((app, query, cb) -> cb.equal(app.get("teamId"), teamId));
        }
        if (withoutTeam) {
            clauses.add((app, query, cb) -> cb.isNull(app.get("teamId")));
        }
        if (cloudProvider != null) {
            clauses.add((app, query, cb) -> cb.equal(app.get("cloudProvider"), cloudProvider));
        }
        if (q != null) {
            String pattern = "%" + escapeLike(q.toLowerCase(Locale.ROOT)) + "%";
            clauses.add((app, query, cb) -> cb.or(
                    cb.like(cb.lower(app.get("name")), pattern, '!'),
                    cb.like(cb.lower(app.get("slug")), pattern, '!')));
        }
        return Specification.allOf(clauses);
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
