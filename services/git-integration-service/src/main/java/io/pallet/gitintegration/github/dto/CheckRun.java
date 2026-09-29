package io.pallet.gitintegration.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** A check run as {@code POST}/{@code PATCH /repositories/{id}/check-runs} answer it, trimmed to what is matched on. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CheckRun(
        long id, String name, @JsonProperty("external_id") String externalId) {

    /** {@code GET /repositories/{id}/commits/{sha}/check-runs}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Page(
            @JsonProperty("total_count") int totalCount,
            @JsonProperty("check_runs") List<CheckRun> checkRuns) {}
}
